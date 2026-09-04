plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.appfusion.product"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appfusion.product"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha01"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
    // Public shared factories expose Room builder/database types, so the app
    // must carry their ABI dependencies on its compile classpath as well.
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.android)
}
