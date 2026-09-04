package com.appfusion.product

import android.app.Application

class FusionApplication : Application() {
    val documentRuntime: AndroidDocumentRuntime by lazy {
        AndroidDocumentRuntime(applicationContext)
    }
}
