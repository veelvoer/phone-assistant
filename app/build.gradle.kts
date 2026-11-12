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
        versionCode = 37
        versionName = "1.24.0"
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
    testOptions { unitTests { isIncludeAndroidResources = true; all { it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true } } } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
