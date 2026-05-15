// app/build.gradle.kts  (Module: app)
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.bgi.pathfinder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bgi.pathfinder"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Android Core (matching your libs.versions.toml)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)

    // OSMDroid (OpenStreetMap)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Retrofit (for structured API calls)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")

    // Protocol Buffers (decode binary stream from backend)
    implementation("com.google.protobuf:protobuf-javalite:4.29.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Lifecycle (for lifecycleScope)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Google Play Services Location (for SOS FAB — FusedLocationProviderClient)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
