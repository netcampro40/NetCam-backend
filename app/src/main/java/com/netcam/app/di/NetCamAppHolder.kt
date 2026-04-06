package com.netcam.app.di

import android.app.Application

/**
 * Holder simples para expor o [Application] como contexto de app
 * para componentes da camada de dados que não recebem injeção direta.
 */
object NetCamAppHolder {
    @Volatile
    var appContext: Application? = null
}

