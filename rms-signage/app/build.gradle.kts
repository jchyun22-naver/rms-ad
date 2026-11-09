plugins {
    id("com.android.application")
}

android {
    namespace = "com.tna.rmssignage"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.tna.rmssignage"
        minSdk = 28
        targetSdk = 28
        versionCode = 2
        versionName = "0.2-tv-preview"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
