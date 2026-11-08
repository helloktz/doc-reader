plugins { id("com.android.application") }

android {
    namespace = "io.github.helloktz.docreader"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    ndkVersion = "30.0.14904198"
    defaultConfig {
        applicationId = "io.github.helloktz.docreader"
        minSdk = 34
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "io.github.helloktz.docreader.DocumentIntegrationTest"
    }
    signingConfigs {
        create("localRelease") {
            val path = System.getenv("DOCREADER_KEYSTORE")
            if (!path.isNullOrEmpty()) {
                storeFile = file(path)
                storePassword = System.getenv("DOCREADER_KEY_PASSWORD")
                keyAlias = "doc-reader"
                keyPassword = System.getenv("DOCREADER_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Compress the two native ABIs for the sideload APK; Android extracts only its own ABI.
    packaging { jniLibs { useLegacyPackaging = true } }
}
