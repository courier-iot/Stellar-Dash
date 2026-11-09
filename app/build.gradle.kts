plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes these in from the release tag (v1.2.3 -> name 1.2.3, code 10203).
val ciVersionName: String? = System.getenv("VERSION_NAME")
val ciVersionCode: Int? = System.getenv("VERSION_CODE")?.toIntOrNull()
val signingStore: String? = System.getenv("SIGNING_STORE_FILE")

android {
    namespace = "com.stellarite.stellardash"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stellarite.stellardash"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (signingStore != null) {
                // Trim stray spaces/newlines pasted into secrets. Modern keystores (PKCS12) use
                // one password for store and key, so an empty KEY_PASSWORD falls back to it.
                val storePw = System.getenv("SIGNING_STORE_PASSWORD")?.trim().orEmpty()
                val keyPw = System.getenv("SIGNING_KEY_PASSWORD")?.trim().orEmpty()
                storeFile = file(signingStore)
                storePassword = storePw
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.trim()
                keyPassword = keyPw.ifEmpty { storePw }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingStore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
