plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "vn.tlu.sosmesh.coremap"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val roomVersion = "2.6.1"
    api("androidx.activity:activity-ktx:1.9.3")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    api("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    api("org.maplibre.gl:android-sdk:11.11.0")
    api("com.google.android.gms:play-services-location:21.3.0")
    api("com.google.android.gms:play-services-nearby:19.3.0")
    api("androidx.room:room-runtime:$roomVersion")
    api("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    api(platform("com.google.firebase:firebase-bom:33.5.1"))
    api("com.google.firebase:firebase-firestore-ktx")
    api("com.google.firebase:firebase-database-ktx")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
