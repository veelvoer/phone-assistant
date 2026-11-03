plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.phoneassistant"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.phoneassistant"
        minSdk = 30
        targetSdk = 34
        versionCode = 16
        versionName = "1.11.0"
    }
    val ks = System.getenv("KEYSTORE_FILE")
    if (ks != null) signingConfigs.create("release") {
        storeFile = file(ks); storePassword = System.getenv("KEYSTORE_PASS")
        keyAlias = "key"; keyPassword = System.getenv("KEYSTORE_PASS"); storeType = "pkcs12"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (ks != null) "release" else "debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
