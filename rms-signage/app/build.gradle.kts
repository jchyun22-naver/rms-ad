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
        versionCode = 3
        versionName = "0.3-tv-preview"
    }
    signingConfigs {
        getByName("debug") {
            val localDebugKey = file("${rootProject.projectDir}/debug.keystore")
            if (localDebugKey.isFile) {
                storeFile = localDebugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
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
