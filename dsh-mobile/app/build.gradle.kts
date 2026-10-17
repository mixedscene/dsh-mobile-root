plugins {
    id("com.android.application")
}

android {
    namespace = "com.dshmobile.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.mixedscene.dshroot"
        minSdk = 26
        targetSdk = 36
        versionCode = 42
        versionName = "0.1.0-root-preview"
        ndk { abiFilters += "arm64-v8a" }
}
    val signingPath = System.getenv("DSH_KEYSTORE_PATH")
    if (!signingPath.isNullOrBlank()) {
        signingConfigs.create("cloud") {
            storeFile = file(signingPath)
            storePassword = System.getenv("DSH_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DSH_KEY_ALIAS")
            keyPassword = System.getenv("DSH_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (!signingPath.isNullOrBlank()) signingConfig = signingConfigs.getByName("cloud")
        }
        debug { applicationIdSuffix = ".debug" }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}
