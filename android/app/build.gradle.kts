import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
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


    buildFeatures { compose = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // the screenshot tests render the real screens with the app's own fonts
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2048m" }
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // Robolectric and Roborazzi render the real Compose screens on the build
    // machine, so the interface can be seen and checked without a phone
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.75.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.75.0")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
