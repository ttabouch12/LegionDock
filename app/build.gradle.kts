plugins {
    id("com.android.application")
}

// Release signing comes from environment variables (set as GitHub secrets in CI).
// Without them, release builds are signed with the debug key so anyone can build and install.
val ksFile = System.getenv("LEGIONDOCK_KEYSTORE")
val ksPass = System.getenv("LEGIONDOCK_KEYSTORE_PASSWORD")

android {
    namespace = "io.legiondock"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.legiondock"
        minSdk = 30          // Android 11+
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"
    }

    signingConfigs {
        if (ksFile != null && ksPass != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = ksPass
                keyAlias = "legiondock"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = false
    }
}
