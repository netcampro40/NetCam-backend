/*
 * NetCam BLE Controller MVP+ (ESP32-C3 + 1 botão + LED + leitura de bateria)
 *
 * Gestos:
 * - 1 clique              -> CLIP_1M
 * - 2 cliques (<= 400 ms) -> CLIP_2M
 * - segurar 3s            -> CLIP_SESSION
 *
 * Hardware:
 * - Botão: GPIO 7 <-> GND (INPUT_PULLUP)
 * - LED: GPIO 21 -> resistor em série -> anodo LED, catodo -> GND
 *
 * Bateria:
 * - Divisor R1=R2=100k (LiPo+ -> nó ADC -> GPIO 0; nó -> GND via R2)
 * - 100nF do nó ADC para GND
 * - Vbatt ≈ 2 * tensão no pino ADC
 * - GPIO 0 = ADC1_CH0 no ESP32-C3 (pino ADC válido)
 */

#include <Arduino.h>
#include <cstdio>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

// -----------------------------
// Configuração de hardware
// -----------------------------
static const uint8_t BUTTON_PIN = 7;
static const bool BUTTON_PRESSED_LEVEL = LOW;
static const uint8_t LED_PIN = 21;

// -----------------------------
// Bateria: ADC no GPIO 0 — divisor R1=R2=100k => Vbatt = Vpin * 2
// -----------------------------
static const uint8_t BATTERY_ADC_PIN = 0;
/** Resolução do ADC (12 bits no core Arduino ESP32). */
static const uint8_t BATTERY_ADC_RESOLUTION_BITS = 12;
/** Tensão de referência do ADC (VDDA nominal no ESP32). */
static const float BATTERY_ADC_VREF_VOLTS = 3.3f;
/** Valor máximo do ADC na resolução configurada (2^n - 1). */
static const float BATTERY_ADC_MAX =
    (float)((1UL << BATTERY_ADC_RESOLUTION_BITS) - 1UL);
/** Ganho do divisor resistivo (R1+R2)/R2 com R1=R2. */
static const float BATTERY_DIVIDER_GAIN = 2.0f;
/** Número de amostras por leitura (média para reduzir ruído). */
static const uint8_t BATTERY_SAMPLE_COUNT = 10;
/** Atenuação do ADC: 11 dB ~ 0–3,3 V no pino (full scale). */
static const adc_attenuation_t BATTERY_ADC_ATTEN = ADC_11db;
/** Intervalo entre leituras completas (ms) — não bloqueia o loop. */
static const uint32_t BATTERY_READ_INTERVAL_MS = 5000UL;

/** Curva LiPo (aproximação): 3.00V=0%, 3.30V≈10%, 3.70V≈50%, 4.20V=100%. */
static const float LIPO_EMPTY_V = 3.00f;
static const float LIPO_P10_V = 3.30f;
static const float LIPO_P50_V = 3.70f;
static const float LIPO_FULL_V = 4.20f;

// -----------------------------
// Configuração de timing (gestos / LED)
// -----------------------------
static const uint32_t DEBOUNCE_MS = 50;
static const uint32_t DOUBLE_CLICK_WINDOW_MS = 400;
static const uint32_t LONG_PRESS_MS = 3000;

// -----------------------------
// Configuração BLE (edite aqui)
// -----------------------------
static const char *DEVICE_NAME = "NetCamPro_CTRL";
static const char *SERVICE_UUID = "6f0a0001-8f9b-4c6a-9d55-1f2b3c4d5e01";
static const char *CHAR_UUID = "6f0a0002-8f9b-4c6a-9d55-1f2b3c4d5e01";

// -----------------------------
// Feedback LED
// -----------------------------
static const uint16_t LED_SHORT_ON_MS = 90;
static const uint16_t LED_SHORT_OFF_MS = 100;
static const uint16_t LED_LONG_ON_MS = 3000;
static const uint16_t LED_LONG_OFF_MS = 120;

static BLEServer *g_server = nullptr;
static BLECharacteristic *g_characteristic = nullptr;
static bool g_deviceConnected = false;

// Estado debounce botão
static bool g_lastRawLevel = HIGH;
static bool g_debouncedLevel = HIGH;
static uint32_t g_lastRawChangeMs = 0;

// Estado gestos
static bool g_pressing = false;
static uint32_t g_pressStartMs = 0;
static bool g_longPressFired = false;
static uint8_t g_clickCount = 0;
static uint32_t g_firstClickReleaseMs = 0;

// Estado LED não-bloqueante
enum class LedPatternType {
  NONE,
  SHORT_BLINKS,
  LONG_BLINKS,
};

static LedPatternType g_ledPatternType = LedPatternType::NONE;
static uint8_t g_ledTotalBlinks = 0;
static uint8_t g_ledCurrentBlink = 0;
static bool g_ledIsOn = false;
static uint32_t g_ledNextToggleMs = 0;

// -----------------------------
// Bateria: estado não bloqueante
// -----------------------------
static uint32_t g_lastBatteryReadMs = 0;

struct BatteryReadout {
  uint16_t adcRaw;
  float adcVoltage;
  float batteryVoltage;
  uint8_t batteryPercent;
};

void setupBLE();
void updateButtonAndGestures();
void handleStableEdge(bool newLevel, uint32_t nowMs);
void sendCommand(const char *command);
void resetClickWindow();

void setupLed();
void updateLed();
void startShortBlinkPattern(uint8_t blinkCount);
void startLongBlinkPattern(uint8_t blinkCount);
void setLed(bool on);

// --- Bateria (API) ---
void setupBatteryAdc();
BatteryReadout readBatteryAveraged();
uint16_t sampleAdcRaw();
float adcRawToPinVoltage(uint16_t raw);
float pinVoltageToBatteryVoltage(float pinVolts);
uint8_t batteryVoltageToPercent(float vBatt);
void printBatteryReadout(const BatteryReadout &r);
void updateBatteryPeriodic(uint32_t nowMs);
void notifyBatteryPercentBle(uint8_t percent);

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *pServer) override {
    (void)pServer;
    g_deviceConnected = true;
    Serial.println("[BLE] Cliente conectado");
    startShortBlinkPattern(1);
  }

  void onDisconnect(BLEServer *pServer) override {
    (void)pServer;
    g_deviceConnected = false;
    Serial.println("[BLE] Cliente desconectado. Reiniciando advertising...");
    BLEDevice::startAdvertising();
  }
};

void setup() {
  Serial.begin(115200);
  delay(120);

  pinMode(BUTTON_PIN, INPUT_PULLUP);
  g_lastRawLevel = digitalRead(BUTTON_PIN);
  g_debouncedLevel = g_lastRawLevel;
  g_lastRawChangeMs = millis();

  setupBatteryAdc();
  g_lastBatteryReadMs = millis();

  setupLed();
  setupBLE();

  // Feedback rápido de boot
  startShortBlinkPattern(2);

  Serial.println("[SYS] Firmware pronto.");
  Serial.println("[SYS] Gestos: 1 clique=CLIP_1M | 2 cliques=CLIP_2M | segurar 3s=CLIP_SESSION");
  Serial.println("[SYS] Bateria: GPIO0 + divisor 1:2, média 10 amostras / 5s, não bloqueante.");
}

void loop() {
  const uint32_t nowMs = millis();
  updateButtonAndGestures();
  updateLed();
  updateBatteryPeriodic(nowMs);
  yield();  // alta responsividade sem delay fixo bloqueante
}

void setupBLE() {
  BLEDevice::init(DEVICE_NAME);

  g_server = BLEDevice::createServer();
  g_server->setCallbacks(new ServerCallbacks());

  BLEService *service = g_server->createService(SERVICE_UUID);
  g_characteristic = service->createCharacteristic(
      CHAR_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);

  g_characteristic->addDescriptor(new BLE2902());
  g_characteristic->setValue("READY");

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println("[BLE] Server inicializado e advertising ativo");
  Serial.print("[BLE] Nome: ");
  Serial.println(DEVICE_NAME);
  Serial.print("[BLE] Service UUID: ");
  Serial.println(SERVICE_UUID);
  Serial.print("[BLE] Characteristic UUID: ");
  Serial.println(CHAR_UUID);
}

void updateButtonAndGestures() {
  const uint32_t nowMs = millis();
  const bool rawLevel = digitalRead(BUTTON_PIN);

  if (rawLevel != g_lastRawLevel) {
    g_lastRawLevel = rawLevel;
    g_lastRawChangeMs = nowMs;
  }

  if ((nowMs - g_lastRawChangeMs) >= DEBOUNCE_MS && g_debouncedLevel != g_lastRawLevel) {
    g_debouncedLevel = g_lastRawLevel;
    handleStableEdge(g_debouncedLevel, nowMs);
  }

  // Long press detectado com botão segurado
  if (g_pressing && !g_longPressFired && (nowMs - g_pressStartMs) >= LONG_PRESS_MS) {
    g_longPressFired = true;
    resetClickWindow();
    Serial.println("[GESTURE] Long press (>=3s)");
    sendCommand("CLIP_SESSION");
    startLongBlinkPattern(1);
  }

  // Confirma clique simples ao fim da janela do duplo
  if (g_clickCount == 1 && (nowMs - g_firstClickReleaseMs) > DOUBLE_CLICK_WINDOW_MS) {
    Serial.println("[GESTURE] Clique simples");
    sendCommand("CLIP_1M");
    startShortBlinkPattern(1);
    resetClickWindow();
  }
}

void handleStableEdge(bool newLevel, uint32_t nowMs) {
  const bool isPressed = (newLevel == BUTTON_PRESSED_LEVEL);

  if (isPressed) {
    g_pressing = true;
    g_pressStartMs = nowMs;
    g_longPressFired = false;
    return;
  }

  if (!g_pressing) return;
  g_pressing = false;

  // Ignora soltura após long press
  if (g_longPressFired) {
    g_longPressFired = false;
    return;
  }

  if (g_clickCount == 0) {
    g_clickCount = 1;
    g_firstClickReleaseMs = nowMs;
  } else if (g_clickCount == 1) {
    Serial.println("[GESTURE] Clique duplo");
    sendCommand("CLIP_2M");
    startShortBlinkPattern(2);
    resetClickWindow();
  }
}

void resetClickWindow() {
  g_clickCount = 0;
  g_firstClickReleaseMs = 0;
}

void sendCommand(const char *command) {
  if (g_characteristic == nullptr) {
    Serial.print("[ERR] Characteristic BLE nula. Comando perdido: ");
    Serial.println(command);
    return;
  }

  g_characteristic->setValue(command);

  if (g_deviceConnected) {
    g_characteristic->notify();
    Serial.print("[CMD] ");
    Serial.print(command);
    Serial.println(" (notify enviado)");
  } else {
    Serial.print("[CMD] ");
    Serial.print(command);
    Serial.println(" (sem cliente BLE conectado; valor disponível para READ)");
  }
}

void setupLed() {
  pinMode(LED_PIN, OUTPUT);
  setLed(false);
}

void updateLed() {
  if (g_ledPatternType == LedPatternType::NONE) return;

  const uint32_t nowMs = millis();
  if (nowMs < g_ledNextToggleMs) return;

  if (!g_ledIsOn) {
    setLed(true);
    g_ledNextToggleMs = nowMs +
                        (g_ledPatternType == LedPatternType::LONG_BLINKS ? LED_LONG_ON_MS : LED_SHORT_ON_MS);
    return;
  }

  setLed(false);
  g_ledCurrentBlink++;

  if (g_ledCurrentBlink >= g_ledTotalBlinks) {
    g_ledPatternType = LedPatternType::NONE;
    g_ledCurrentBlink = 0;
    g_ledTotalBlinks = 0;
    g_ledNextToggleMs = 0;
    return;
  }

  g_ledNextToggleMs = nowMs +
                      (g_ledPatternType == LedPatternType::LONG_BLINKS ? LED_LONG_OFF_MS : LED_SHORT_OFF_MS);
}

void startShortBlinkPattern(uint8_t blinkCount) {
  if (blinkCount == 0) return;
  g_ledPatternType = LedPatternType::SHORT_BLINKS;
  g_ledTotalBlinks = blinkCount;
  g_ledCurrentBlink = 0;
  g_ledIsOn = false;
  g_ledNextToggleMs = millis();
}

void startLongBlinkPattern(uint8_t blinkCount) {
  if (blinkCount == 0) return;
  g_ledPatternType = LedPatternType::LONG_BLINKS;
  g_ledTotalBlinks = blinkCount;
  g_ledCurrentBlink = 0;
  g_ledIsOn = false;
  g_ledNextToggleMs = millis();
}

void setLed(bool on) {
  g_ledIsOn = on;
  digitalWrite(LED_PIN, on ? HIGH : LOW);
}

// =============================================================================
// Bateria — implementação
// =============================================================================

void setupBatteryAdc() {
  pinMode(BATTERY_ADC_PIN, INPUT);
  analogSetPinAttenuation(BATTERY_ADC_PIN, BATTERY_ADC_ATTEN);
  analogReadResolution(BATTERY_ADC_RESOLUTION_BITS);
  Serial.print("[BAT] ADC inicializado no GPIO ");
  Serial.print(BATTERY_ADC_PIN);
  Serial.print(", resolução ");
  Serial.print(BATTERY_ADC_RESOLUTION_BITS);
  Serial.println(" bits, attenuation 11 dB");
}

uint16_t sampleAdcRaw() {
  return (uint16_t)analogRead(BATTERY_ADC_PIN);
}

float adcRawToPinVoltage(uint16_t raw) {
  return (float)raw * (BATTERY_ADC_VREF_VOLTS / BATTERY_ADC_MAX);
}

float pinVoltageToBatteryVoltage(float pinVolts) {
  return pinVolts * BATTERY_DIVIDER_GAIN;
}

/** Interpolação linear por segmentos: 3.0→0%, 3.3→10%, 3.7→50%, 4.2→100%. */
uint8_t batteryVoltageToPercent(float vBatt) {
  if (vBatt <= LIPO_EMPTY_V) {
    return 0;
  }
  if (vBatt >= LIPO_FULL_V) {
    return 100;
  }

  float p = 0.f;

  if (vBatt < LIPO_P10_V) {
    // 3.00 .. 3.30 -> 0% .. 10%
    p = (vBatt - LIPO_EMPTY_V) * (10.0f / (LIPO_P10_V - LIPO_EMPTY_V));
  } else if (vBatt < LIPO_P50_V) {
    // 3.30 .. 3.70 -> 10% .. 50%
    p = 10.0f + (vBatt - LIPO_P10_V) * (40.0f / (LIPO_P50_V - LIPO_P10_V));
  } else {
    // 3.70 .. 4.20 -> 50% .. 100%
    p = 50.0f + (vBatt - LIPO_P50_V) * (50.0f / (LIPO_FULL_V - LIPO_P50_V));
  }

  if (p < 0.f) p = 0.f;
  if (p > 100.f) p = 100.f;
  return (uint8_t)(p + 0.5f);
}

BatteryReadout readBatteryAveraged() {
  uint32_t sum = 0;
  for (uint8_t i = 0; i < BATTERY_SAMPLE_COUNT; i++) {
    sum += sampleAdcRaw();
    delayMicroseconds(200);
  }
  const uint16_t raw = (uint16_t)(sum / BATTERY_SAMPLE_COUNT);

  BatteryReadout out;
  out.adcRaw = raw;
  out.adcVoltage = adcRawToPinVoltage(raw);
  out.batteryVoltage = pinVoltageToBatteryVoltage(out.adcVoltage);
  out.batteryPercent = batteryVoltageToPercent(out.batteryVoltage);
  return out;
}

void printBatteryReadout(const BatteryReadout &r) {
  Serial.print("[BAT] raw=");
  Serial.print(r.adcRaw);
  Serial.print(" | Vpin=");
  Serial.print(r.adcVoltage, 3);
  Serial.print(" V | Vbatt=");
  Serial.print(r.batteryVoltage, 3);
  Serial.print(" V | SOC≈");
  Serial.print(r.batteryPercent);
  Serial.println("%");
}

/** Notificação BLE no formato esperado pelo app NetCamPro (Android). */
void notifyBatteryPercentBle(uint8_t percent) {
  if (g_characteristic == nullptr || !g_deviceConnected) {
    return;
  }
  char buf[20];
  snprintf(buf, sizeof(buf), "BATTERY:%u", (unsigned)percent);
  g_characteristic->setValue(buf);
  g_characteristic->notify();
  Serial.print("[BAT] BLE notify: ");
  Serial.println(buf);
}

void updateBatteryPeriodic(uint32_t nowMs) {
  if ((uint32_t)(nowMs - g_lastBatteryReadMs) < BATTERY_READ_INTERVAL_MS) {
    return;
  }
  g_lastBatteryReadMs = nowMs;

  BatteryReadout r = readBatteryAveraged();
  printBatteryReadout(r);
  notifyBatteryPercentBle(r.batteryPercent);
}
