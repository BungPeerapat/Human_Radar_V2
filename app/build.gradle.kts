plugins {
    alias(libs.plugins.android.application)
}

// Version derived from env (CI sets these from the git tag) or defaults for local builds.
val appVersionName: String = System.getenv("APP_VERSION_NAME") ?: "1.0.0"
val appVersionCode: Int = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()

// Manifest URL is required for the in-app updater. Override per build via env or
// gradle property; placeholder makes the updater short-circuit with DISABLED.
val updateManifestUrl: String =
    System.getenv("UPDATE_MANIFEST_URL")
        ?: (project.findProperty("UPDATE_MANIFEST_URL") as String?)
        ?: "https://REPLACE_ME.example.com/update.json"

android {
    namespace = "com.example.radarhumanapplication"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.radarhumanapplication"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            val keystorePass = System.getenv("KEYSTORE_PASSWORD")
            val keystoreAlias = System.getenv("KEY_ALIAS")
            val keystoreKeyPass = System.getenv("KEY_PASSWORD")
            if (keystorePath != null && keystorePass != null
                && keystoreAlias != null && keystoreKeyPass != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use release signing only when keystore env vars are present (CI). Local
            // release builds without env will fall back to no signing config.
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.hivemq.mqtt)
    implementation(libs.gson)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
