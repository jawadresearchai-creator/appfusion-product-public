import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidxRoom3)
    alias(libs.plugins.cryptography)
}

cryptography {
    configureSwiftLinkerOpts = true
}

kotlin {
    jvm()

    android {
        namespace = "com.appfusion.product.shared"
        compileSdk = 36
        minSdk = 26
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            execution = "HOST"
        }
    }

    val iosDevice = iosArm64()
    val iosSimulator = iosSimulatorArm64()

    iosDevice.binaries.framework {
        baseName = "AppFusionShared"
        isStatic = true
    }
    iosSimulator.binaries.framework {
        baseName = "AppFusionShared"
        // The runtime probe embeds this framework in a simulator-installed host app.
        // Keep the device artifact static; use a dynamic simulator framework so the
        // Kotlin/Native linker, rather than a raw Swift static-link step, owns all
        // native transitive dependencies needed by the probe.
        isStatic = false
    }

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        named("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.junit)
            }
        }
    }
}

dependencies {
    add("kspJvm", libs.androidx.room3.compiler)
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspIosArm64", libs.androidx.room3.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}
