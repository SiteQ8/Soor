plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eworldq8.soor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.eworldq8.soor"
        minSdk = 24
        targetSdk = 36
        // CI numbers every build it sends to Play, because Play refuses a version
        // code it has already seen; a local build stays at 1, the first upload
        versionCode = (System.getenv("SOOR_VERSION_CODE") ?: "1").toInt()
        versionName = "1.0"
        resourceConfigurations += listOf("ar", "en")
    }

    // The upload key is never stored in the repository. A release build reads
    // it from the environment: a local keystore file, or the CI secret.
    val keystorePath: String? = System.getenv("SOOR_KEYSTORE")
    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SOOR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SOOR_KEY_ALIAS") ?: "soor-upload"
                keyPassword = System.getenv("SOOR_KEY_PASSWORD") ?: System.getenv("SOOR_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
