import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.edutube.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.edutube.app"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "2.0.$buildNumber"

        // مكتبة تيليجرام كبيرة: نكتفي بمعالجات الهواتف الشائعة
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("edutube.jks")
            storePassword = "edutube123"
            keyAlias = "edutube"
            keyPassword = "edutube123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // ملفات المصدر في مجلد src بجذر المستودع
    sourceSets {
        getByName("main") {
            manifest.srcFile("../src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("../src/main/java"))
            res.setSrcDirs(listOf("../src/main/res"))
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("io.coil-kt:coil:2.7.0")
    // تيليجرام (TDLib الرسمية مع واجهة Kotlin)
    implementation("dev.g000sha256:tdl-coroutines:18.0.0")
}
