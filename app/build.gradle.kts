plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cisiur.nannycamviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cisiur.nannycamviewer"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val media3Version = "1.9.4"

    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    testImplementation("junit:junit:4.13.2")
}
