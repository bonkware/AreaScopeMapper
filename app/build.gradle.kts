plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.benasafrique.areascopemapper"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.benasafrique.areascopemapper"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.6"

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        //compose = true
        viewBinding = true
    }
}

dependencies {
    // Core AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.databinding.runtime)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Location provider for GPS
    implementation(libs.play.services.location)

    // OSMDroid (OpenStreetMap) core
    implementation(libs.osmdroid)
    implementation(libs.osmdroidWms)
    implementation(libs.osmdroidMapsforge)

    // Optional: OSMDroid GeoPackage (offline GeoPackage support)
    // Exclude ORMLite to avoid duplicate class errors
    /*implementation(libs.osmdroidGeopackage) {
        isTransitive = true
        exclude(group = "com.j256.ormlite", module = "ormlite-core")
        exclude(group = "com.j256.ormlite", module = "ormlite-android")
    }*/

    // JSON serialization
    implementation(libs.gson)
    implementation(libs.androidx.preference.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
