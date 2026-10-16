@file:Suppress("UnstableApiUsage")
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.spotless)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
}

val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

val secretsProps =
    Properties().apply {
        val f = rootProject.file("secrets.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

android {
    namespace = "com.github.ilife798"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.ilife798"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "1.3.5"

        buildConfigField("String", "API_GATEWAY", "\"${secretsProps.getProperty("API_GATEWAY", "")}\"")
        buildConfigField("String", "SIGN_SALT", "\"${secretsProps.getProperty("SIGN_SALT", "")}\"")
        buildConfigField("String", "API_CID", "\"${secretsProps.getProperty("API_CID", "")}\"")
    }
    androidResources {
        localeFilters += listOf("zh")
    }
    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "DebugProbesKt.bin",
                    "kotlin-tooling-metadata.json",
                )
        }
    }
    val releaseStoreFile = localProps.getProperty("signing.storeFile", "")
    if (releaseStoreFile.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localProps.getProperty("signing.storePassword", "")
                keyAlias = localProps.getProperty("signing.keyAlias", "")
                keyPassword = localProps.getProperty("signing.keyPassword", "")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseStoreFile.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
}

val appName = rootProject.name.lowercase()

base {
    archivesName = "$appName-android"
}
