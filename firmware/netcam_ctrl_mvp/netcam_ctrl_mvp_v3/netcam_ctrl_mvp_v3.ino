/*
 * NetCam BLE Controller V3 (ESP32 + 1 botão + LED + bateria opcional)
 * V3: até 5 celulares conectados simultaneamente; mesmo comando notify para todos.
 *
 * Gestos:
 * - 1 clique              -> CLIP_1M
 * - 2 cliques (<= 400 ms) -> CLIP_2M
 * - segurar 3s            -> CLIP_SESSION
 *
 * Hardware:
 * - Botão: GPIO 18 <-> GND (INPUT_PULLUP)
 * - LED: GPIO 2 -> resistor em série -> anodo LED, catodo -> GND
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
#include <esp_gap_ble_api.h>
#if __has_include(<esp_bt_main.h>)
#include <esp_bt_main.h>
#define NETCAM_HAS_BT_MAIN 1
#else
#define NETCAM_HAS_BT_MAIN 0
#endif

// -----------------------------
// Configuração de hardware
// -----------------------------
static const uint8_t BUTTON_PIN = 18;
static const bool BUTTON_PRESSED_LEVEL = LOW;
static const uint8_t LED_PIN = 2;

// -----------------------------
// Bateria (opcional):
// - ADC no GPIO 0 — divisor R1=R2=100k => Vbatt = Vpin * 2
// - Em DevKit com 18650, pode ficar desativado para teste de alcance BLE.
// - GPIO0 só deve ser usado para VBAT se houver divisor externo/trilha dedicada com segurança.
// -----------------------------
static const bool ENABLE_BATTERY_MONITOR = false;
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
/** Nome GAP — manter "NetCamPro_CTRL" para compatibilidade com app Android/iOS. */
static const char *DEVICE_NAME = "NetCamPro_CTRL";
/** ID fixo desta unidade física (NETCAM_CTRL_002, etc. em outras placas). */
static const char *CONTROL_UNIT_ID = "NETCAM_CTRL_001";
/** Máximo de celulares conectados ao mesmo tempo (V3). */
static const uint8_t MAX_BLE_CLIENTS = 5;
static const char *SERVICE_UUID = "6f0a0001-8f9b-4c6a-9d55-1f2b3c4d5e01";
static const char *CHAR_UUID = "6f0a0002-8f9b-4c6a-9d55-1f2b3c4d5e01";
/** Modo de teste para alcance BLE: desativa sleep do BT se API disponível. */
static const bool BLE_RANGE_TEST_MODE = true;
/** Janela de proteção para não intercalar notify de bateria logo após comando de clipe. */
static const uint32_t BATTERY_NOTIFY_GUARD_AFTER_COMMAND_MS = 400UL;
/** Atraso antes de reiniciar advertising (evita chamar BLE dentro do callback). */
static const uint32_t BLE_ADV_RESTART_DELAY_MS = 200UL;
/** Intervalo do watchdog BLE no loop principal. */
static const uint32_t BLE_WATCHDOG_INTERVAL_MS = 3000UL;
/** Keepalive de advertising quando há vaga para novos clientes. */
static const uint32_t BLE_ADV_KEEPALIVE_INTERVAL_MS = 8000UL;
/** Pausa entre stop/start no hard restart de advertising. */
static const uint32_t BLE_ADV_HARD_RESTART_GAP_MS = 80UL;

// -----------------------------
// Feedback LED
// -----------------------------
static const uint16_t LED_SHORT_ON_MS = 90;
static const uint16_t LED_SHORT_OFF_MS = 100;
static const uint16_t LED_LONG_ON_MS = 3000;
static const uint16_t LED_LONG_OFF_MS = 120;

static BLEServer *g_server = nullptr;
static BLECharacteristic *g_characteristic = nullptr;
static uint8_t g_connectedClientCount = 0;
static uint32_t g_commandSequence = 0;
static uint32_t g_lastCommandNotifyMs = 0;
static bool g_bleAdvRestartPending = false;
static uint32_t g_bleAdvRestartAtMs = 0;
static bool g_bleAdvHardRestartPending = false;
static uint32_t g_bleAdvHardRestartAtMs = 0;
static uint32_t g_lastBleWatchdogMs = 0;
static uint32_t g_lastBleAdvKeepaliveMs = 0;
static char g_bleAdvRestartReason[40] = "init";

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
void processBleMaintenance(uint32_t nowMs);
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

static uint8_t getConnectedClientCount();
static bool hasAnyBleClient();

static const char *bleDisconnectReasonText(uint8_t reason) {
  switch (reason) {
    case 0x08: return "supervision_timeout";
    case 0x13: return "remote_user_terminated";
    case 0x16: return "local_host_terminated";
    case 0x19: return "connection_terminated_by_peer";
    case 0x22: return "lmp_response_timeout";
    case 0x3e: return "conn_fail_establish";
    default: return "unknown";
  }
}

static uint8_t countActivePeerDevices() {
  if (g_server == nullptr) return 0;
  const std::map<uint16_t, conn_status_t> peers = g_server->getPeerDevices(false);
  uint8_t active = 0;
  for (const auto &entry : peers) {
    if (entry.second.connected) {
      active++;
    }
  }
  return active;
}

static void logBleConnectedCount(const char *context) {
  const uint8_t cached = g_connectedClientCount;
  const uint8_t serverCount = g_server != nullptr ? (uint8_t)g_server->getConnectedCount() : 0;
  const uint8_t peerActive = countActivePeerDevices();
  Serial.printf(
      "[BLE] connected count=%u (server=%u peer_active=%u max=%u) ctx=%s\n",
      cached,
      serverCount,
      peerActive,
      (unsigned)MAX_BLE_CLIENTS,
      context);
}

static void syncConnectedClientCount(const char *context) {
  if (g_server == nullptr) return;

  const uint8_t serverCount = (uint8_t)g_server->getConnectedCount();
  const uint8_t peerActive = countActivePeerDevices();
  if (g_connectedClientCount != serverCount || serverCount != peerActive) {
    Serial.printf(
        "[BLE] count mismatch (%s): cached=%u server=%u peer_active=%u\n",
        context,
        g_connectedClientCount,
        serverCount,
        peerActive);
    g_connectedClientCount = serverCount;
    logBleConnectedCount("after_sync");
  }
}

static void cleanupStalePeerDevices() {
  if (g_server == nullptr) return;

  const std::map<uint16_t, conn_status_t> peers = g_server->getPeerDevices(false);
  for (const auto &entry : peers) {
    if (!entry.second.connected) {
      Serial.printf("[BLE] removing stale peer connId=%u\n", entry.first);
      g_server->removePeerDevice(entry.first, false);
    }
  }
}

static void pruneExcessConnections() {
  if (g_server == nullptr) return;

  const std::map<uint16_t, conn_status_t> peers = g_server->getPeerDevices(false);
  if (peers.size() <= MAX_BLE_CLIENTS) return;

  Serial.printf("[BLE] max clients exceeded (peers=%u); pruning extras\n", (unsigned)peers.size());
  uint8_t kept = 0;
  for (const auto &entry : peers) {
    if (kept >= MAX_BLE_CLIENTS) {
      Serial.printf("[BLE] disconnecting excess connId=%u\n", entry.first);
      g_server->disconnect(entry.first);
      continue;
    }
    if (entry.second.connected) {
      kept++;
    }
  }
}

static void startBleAdvertisingNow(const char *reason) {
  const uint8_t count = getConnectedClientCount();
  if (count >= MAX_BLE_CLIENTS) {
    Serial.printf("[BLE] max clients reached (%u/%u) reason=%s\n", count, (unsigned)MAX_BLE_CLIENTS, reason);
    return;
  }

  BLEDevice::startAdvertising();
  Serial.printf("[BLE] advertising started reason=%s connected=%u\n", reason, count);
}

static void scheduleBleAdvertisingRestart(const char *reason, uint32_t delayMs = BLE_ADV_RESTART_DELAY_MS) {
  g_bleAdvRestartPending = true;
  g_bleAdvRestartAtMs = millis() + delayMs;
  snprintf(g_bleAdvRestartReason, sizeof(g_bleAdvRestartReason), "%s", reason);
  Serial.printf("[BLE] advertising restart scheduled reason=%s delay=%ums\n", reason, (unsigned)delayMs);
}

static void scheduleBleAdvertisingHardRestart(const char *reason) {
  if (g_bleAdvHardRestartPending) return;
  g_bleAdvHardRestartPending = true;
  g_bleAdvHardRestartAtMs = millis();
  snprintf(g_bleAdvRestartReason, sizeof(g_bleAdvRestartReason), "%s", reason);
  BLEDevice::stopAdvertising();
  Serial.printf("[BLE] advertising hard restart begin reason=%s\n", reason);
}

static void processBleAdvertisingRestart(uint32_t nowMs) {
  if (g_bleAdvHardRestartPending && (uint32_t)(nowMs - g_bleAdvHardRestartAtMs) >= BLE_ADV_HARD_RESTART_GAP_MS) {
    g_bleAdvHardRestartPending = false;
    syncConnectedClientCount("hard_adv_restart");
    startBleAdvertisingNow(g_bleAdvRestartReason);
    Serial.printf("[BLE] advertising restarted reason=%s\n", g_bleAdvRestartReason);
    return;
  }

  if (g_bleAdvRestartPending && nowMs >= g_bleAdvRestartAtMs) {
    g_bleAdvRestartPending = false;
    syncConnectedClientCount("adv_restart");
    startBleAdvertisingNow(g_bleAdvRestartReason);
    Serial.printf("[BLE] advertising restarted reason=%s\n", g_bleAdvRestartReason);
  }
}

static void updateBleConnectionParams(BLEServer *pServer, esp_ble_gatts_cb_param_t *param) {
  if (pServer == nullptr || param == nullptr) return;
  // Intervalos conservadores + supervision timeout 4s para recuperar apps encerrados abruptamente.
  pServer->updateConnParams(param->connect.remote_bda, 0x18, 0x28, 0, 400);
}

static void updateBleWatchdog(uint32_t nowMs) {
  if ((uint32_t)(nowMs - g_lastBleWatchdogMs) < BLE_WATCHDOG_INTERVAL_MS) return;
  g_lastBleWatchdogMs = nowMs;

  syncConnectedClientCount("watchdog");
  cleanupStalePeerDevices();
  pruneExcessConnections();
  syncConnectedClientCount("watchdog_after_cleanup");

  const uint8_t count = getConnectedClientCount();
  const uint8_t peerActive = countActivePeerDevices();

  if (count == 0 && peerActive > 0) {
    Serial.println("[BLE] watchdog: ghost peers detected with zero server count");
    cleanupStalePeerDevices();
    scheduleBleAdvertisingHardRestart("ghost_zero_count");
    return;
  }

  if (count > 0 && peerActive == 0) {
    Serial.println("[BLE] watchdog: server reports clients but peer map is empty");
    g_connectedClientCount = 0;
    scheduleBleAdvertisingHardRestart("stale_server_count");
    return;
  }

  if (count < MAX_BLE_CLIENTS) {
    if ((uint32_t)(nowMs - g_lastBleAdvKeepaliveMs) >= BLE_ADV_KEEPALIVE_INTERVAL_MS) {
      g_lastBleAdvKeepaliveMs = nowMs;
      if (!g_bleAdvRestartPending && !g_bleAdvHardRestartPending) {
        BLEDevice::startAdvertising();
        Serial.printf("[BLE] advertising keepalive connected=%u\n", count);
      }
    }
  }
}

void processBleMaintenance(uint32_t nowMs) {
  processBleAdvertisingRestart(nowMs);
  updateBleWatchdog(nowMs);
}

static uint8_t getConnectedClientCount() {
  if (g_server == nullptr) return g_connectedClientCount;
  return (uint8_t)g_server->getConnectedCount();
}

static bool hasAnyBleClient() {
  return getConnectedClientCount() > 0;
}

/** Notify via characteristic — no Core 3.3.x notify() já envia a todos os peers conectados. */
static uint8_t notifyCharacteristicToAllClients() {
  if (g_characteristic == nullptr || g_server == nullptr) return 0;

  const uint8_t clientCount = getConnectedClientCount();
  if (clientCount == 0) return 0;

  g_characteristic->notify();
  return clientCount;
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *pServer) override {
    onConnect(pServer, nullptr);
  }

  void onDisconnect(BLEServer *pServer) override {
    onDisconnect(pServer, nullptr);
  }

  void onConnect(BLEServer *pServer, esp_ble_gatts_cb_param_t *param) override {
    const uint16_t connId = param != nullptr ? param->connect.conn_id : 0;
    const uint8_t count = (uint8_t)pServer->getConnectedCount();

    if (count > MAX_BLE_CLIENTS) {
      Serial.printf("[BLE] max clients reached; rejecting connId=%u total=%u\n", connId, count);
      if (param != nullptr) {
        pServer->disconnect(connId);
      }
      scheduleBleAdvertisingRestart("reject_over_limit", 300);
      return;
    }

    g_connectedClientCount = count;
    updateBleConnectionParams(pServer, param);
    Serial.printf("[BLE] client connected connId=%u count=%u/%u\n", connId, count, (unsigned)MAX_BLE_CLIENTS);
    logBleConnectedCount("onConnect");
    startShortBlinkPattern(1);

    if (count >= MAX_BLE_CLIENTS) {
      Serial.println("[BLE] max clients reached");
    } else {
      scheduleBleAdvertisingRestart("onConnect");
    }
  }

  void onDisconnect(BLEServer *pServer, esp_ble_gatts_cb_param_t *param) override {
    const uint16_t connId = param != nullptr ? param->disconnect.conn_id : 0;
    const uint8_t reason = param != nullptr ? param->disconnect.reason : 0;
    g_connectedClientCount = (uint8_t)pServer->getConnectedCount();

    Serial.printf(
        "[BLE] client disconnected connId=%u reason=0x%02X (%s)\n",
        connId,
        reason,
        bleDisconnectReasonText(reason));
    logBleConnectedCount("onDisconnect");

    if (param != nullptr) {
      pServer->removePeerDevice(connId, false);
    }
    syncConnectedClientCount("onDisconnect_cleanup");
    scheduleBleAdvertisingRestart("onDisconnect");
  }
};

void setup() {
  Serial.begin(115200);
  delay(120);

  pinMode(BUTTON_PIN, INPUT_PULLUP);
  g_lastRawLevel = digitalRead(BUTTON_PIN);
  g_debouncedLevel = g_lastRawLevel;
  g_lastRawChangeMs = millis();

  if (ENABLE_BATTERY_MONITOR) {
    setupBatteryAdc();
    g_lastBatteryReadMs = millis();
    Serial.println("[BAT] Monitor de bateria ativo");
  } else {
    Serial.println("[BAT] Monitor de bateria desativado para teste BLE");
  }

  setupLed();
  setupBLE();

  // Feedback rápido de boot
  startShortBlinkPattern(2);

  Serial.println("[SYS] Firmware pronto.");
  Serial.println("[SYS] Gestos: 1 clique=CLIP_1M | 2 cliques=CLIP_2M | segurar 3s=CLIP_SESSION");
  if (ENABLE_BATTERY_MONITOR) {
    Serial.println("[SYS] Bateria: GPIO0 + divisor 1:2, média 10 amostras / 5s, não bloqueante.");
  } else {
    Serial.println("[SYS] Bateria: monitor desativado.");
  }
}

void loop() {
  const uint32_t nowMs = millis();
  processBleMaintenance(nowMs);
  updateButtonAndGestures();
  updateLed();
  if (ENABLE_BATTERY_MONITOR) {
    updateBatteryPeriodic(nowMs);
  }
  yield();  // alta responsividade sem delay fixo bloqueante
}

void setupBLE() {
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setPower(ESP_PWR_LVL_P9);
  Serial.println("[BLE] Potência TX configurada: ESP_PWR_LVL_P9");

#if NETCAM_HAS_BT_MAIN
  if (BLE_RANGE_TEST_MODE) {
    const esp_err_t sleepResult = esp_bt_sleep_disable();
    if (sleepResult == ESP_OK) {
      Serial.println("[BLE] Range test: sleep BT desativado (maior consumo, melhor estabilidade de link)");
    } else {
      Serial.printf("[BLE] Range test: não foi possível desativar sleep BT (err=%d)\n", (int)sleepResult);
    }
  }
#endif

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
  // Scan response: nome GAP (app) + ID da unidade em manufacturer data (não altera filtro por nome).
  {
    BLEAdvertisementData scanResponse;
    scanResponse.setName(DEVICE_NAME);
    scanResponse.setCompleteServices(BLEUUID(SERVICE_UUID));
    scanResponse.setManufacturerData(String(CONTROL_UNIT_ID));
    advertising->setScanResponseData(scanResponse);
  }
  // Intervalos conservadores para facilitar descoberta/reconexão sem quebrar compatibilidade.
  advertising->setMinInterval(0x20);  // 20 ms
  advertising->setMaxInterval(0x40);  // 40 ms
  advertising->setMinPreferred(0x06);
  advertising->setMaxPreferred(0x12);
  startBleAdvertisingNow("boot");
  g_lastBleWatchdogMs = millis();
  g_lastBleAdvKeepaliveMs = millis();

  Serial.println("[BLE] Server inicializado");
  Serial.print("[BLE] Nome GAP (app): ");
  Serial.println(DEVICE_NAME);
  Serial.print("[BLE] ID da unidade: ");
  Serial.println(CONTROL_UNIT_ID);
  Serial.printf("[BLE] Multi-cliente: até %u conexões simultâneas (V3)\n", (unsigned)MAX_BLE_CLIENTS);
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

  g_commandSequence++;
  const uint8_t clientsBefore = getConnectedClientCount();
  if (clientsBefore > 0) {
    const uint8_t peerActive = countActivePeerDevices();
    if (peerActive == 0) {
      Serial.printf(
          "[BLE] notify skipped command=%s connected=%u peer_active=0 (stale)\n",
          command,
          clientsBefore);
      syncConnectedClientCount("notify_skipped");
      scheduleBleAdvertisingHardRestart("notify_stale_peers");
      return;
    }

    const uint8_t notified = notifyCharacteristicToAllClients();
    g_lastCommandNotifyMs = millis();
    Serial.printf(
        "[BLE] notify command=%s connected=%u notified=%u seq=%u\n",
        command,
        clientsBefore,
        notified,
        g_commandSequence);
  } else {
    Serial.printf("[BLE] notify skipped command=%s connected=0\n", command);
  }
  // Futuro: para aumentar robustez de entrega, usar eventId + deduplicação no app antes de retries.
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
  if (g_characteristic == nullptr || !hasAnyBleClient()) {
    return;
  }
  const uint32_t nowMs = millis();
  if ((uint32_t)(nowMs - g_lastCommandNotifyMs) < BATTERY_NOTIFY_GUARD_AFTER_COMMAND_MS) {
    Serial.println("[BAT] notify adiado para evitar colisão com comando recente");
    return;
  }
  char buf[20];
  snprintf(buf, sizeof(buf), "BATTERY:%u", (unsigned)percent);
  g_characteristic->setValue(buf);
  const uint8_t notified = notifyCharacteristicToAllClients();
  Serial.print("[BAT] BLE notify: ");
  Serial.print(buf);
  Serial.print(" -> ");
  Serial.print(notified);
  Serial.println(" cliente(s)");
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
