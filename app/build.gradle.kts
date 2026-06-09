plugins {
    id("com.android.application")
}

android {
    namespace = "boo.deadlight.proxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "boo.deadlight.proxy"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.1.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../../deadlight-release.jks")
            storePassword = System.getenv("STORE_PASS")
            keyAlias = "deadlight"
            keyPassword = System.getenv("KEY_PASS")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}