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
        versionCode = 6
        versionName = "0.5.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
