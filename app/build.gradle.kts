plugins {
    id("com.android.application")
}

android {
    namespace = "com.nothingreader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nothingreader.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
