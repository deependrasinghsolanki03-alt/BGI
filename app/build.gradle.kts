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
        versionCode = 2
        versionName = "2.0"
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
    // Android Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)

    // OSMDroid (map tiles from OSM cloud)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // OkHttp + Retrofit (for ORS cloud API calls)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Lifecycle (for lifecycleScope)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Google Play Services Location (SOS GPS)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
