package com.appfusion.product

import android.app.Application

class FusionApplication : Application() {
    val activityRuntime: AndroidActivityRuntime by lazy {
        AndroidActivityRuntime(applicationContext)
    }
    val documentRuntime: AndroidDocumentRuntime by lazy {
        AndroidDocumentRuntime(applicationContext)
    }
}
