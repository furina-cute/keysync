import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("kotlinx-serialization")
}

android {

    namespace = "com.devoid.keysync"
    compileSdk = 35

    signingConfigs {
        create("release") {
            val storeFileProp = project.findProperty("storeFile") as String?
            val ksFile = storeFileProp?.let { file(it) }
            if (ksFile != null && ksFile.exists()) {
                storeFile = ksFile
                storePassword = (project.findProperty("storePassword") as String?) ?: ""
                keyAlias = (project.findProperty("keyAlias") as String?) ?: ""
                keyPassword = (project.findProperty("keyPassword") as String?) ?: ""
            }
        }
    }


    defaultConfig {
        applicationId = "com.devoid.keysync"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "1.41-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
    }
}

tasks.whenTaskAdded {
    if (name.startsWith("assembleRelease") || name.startsWith("bundleRelease")) {
        doFirst {
            if (android.signingConfigs.getByName("release").storeFile == null) {
                throw GradleException("Release keystore not configured: set storeFile/storePassword/keyAlias/keyPassword in gradle.properties")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.core.splashscreen)
    ksp("com.google.dagger:hilt-android-compiler:2.55")
    implementation("com.google.dagger:hilt-android:2.55")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.api)
    implementation(libs.provider)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}