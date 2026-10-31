plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.pcla.app"
    compileSdk = 34
    // Only need clang + jni.h from the NDK, to hand the Surface to libpojavexec.so
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.pcla.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "0.7.1-alpha"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        // AGP 8 does not generate BuildConfig by default; the top bar reads the version from it
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Legacy packaging: lib*.so is extracted into nativeLibraryDir at install time;
        // Pojav-family dlopen needs a real file path, not just APK mapping.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":download"))
    implementation("org.tukaani:xz:1.9")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui:1.7.3")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.3")
}
