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

// 编译期常量来源优先级：环境变量 → secrets.properties → 空字符串。
// 环境变量优先，便于 CI 用 Secrets 注入而无需落盘；本地开发者写 secrets.properties。
fun resolveBuildSecret(
    envKey: String,
    propKey: String,
): String {
    System.getenv(envKey)?.takeIf { it.isNotBlank() }?.let { return it }
    return secretsProps.getProperty(propKey, "")
}

android {
    namespace = "com.github.ilife798"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.ilife798"
        minSdk = 26
        targetSdk = 37
        versionCode = 16
        versionName = "1.3.9"

        buildConfigField("String", "API_GATEWAY", "\"${resolveBuildSecret("API_GATEWAY", "API_GATEWAY")}\"")
        buildConfigField("String", "SIGN_SALT", "\"${resolveBuildSecret("SIGN_SALT", "SIGN_SALT")}\"")
        buildConfigField("String", "API_CID", "\"${resolveBuildSecret("API_CID", "API_CID")}\"")
        // 微信兜底 appId：与官方一致，但只能通过编译时环境/密钥注入，仓库内不留明文。
        buildConfigField(
            "String",
            "WECHAT_FALLBACK_APP_ID",
            "\"${resolveBuildSecret("WECHAT_FALLBACK_APP_ID", "WECHAT_FALLBACK_APP_ID")}\"",
        )
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
