import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.spotless)
}

aboutLibraries {
    export {
        outputFile = file("src/commonMain/composeResources/files/aboutlibraries.json")
        prettyPrint = true
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.github.ilife798.shared.resources"
}

// —— iOS 构建期常量注入（与 Android secrets.properties 同源；缺省空字符串）——
// 来源优先级：环境变量 ILIFE798_* → 根目录 secrets.properties → 空字符串。
fun resolveIosBuildConfig(
    envKey: String,
    propKey: String,
): String {
    System.getenv(envKey)?.takeIf { it.isNotBlank() }?.let { return it }
    val secretsFile = rootProject.file("secrets.properties")
    if (secretsFile.exists()) {
        val props = Properties()
        secretsFile.inputStream().use { props.load(it) }
        props.getProperty(propKey)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

val iosApiGateway = resolveIosBuildConfig("ILIFE798_API_GATEWAY", "API_GATEWAY")
val iosSignSalt = resolveIosBuildConfig("ILIFE798_SIGN_SALT", "SIGN_SALT")
val iosApiCid = resolveIosBuildConfig("ILIFE798_API_CID", "API_CID")

val generateIosBuildConfig =
    tasks.register("generateIosBuildConfig") {
        description = "生成 iOS 构建期常量（网关/签名盐/CID）"
        val generatedDir = layout.buildDirectory.dir("generated/iosBuildConfig/kotlin")
        val apiGateway = iosApiGateway
        val signSalt = iosSignSalt
        val apiCid = iosApiCid
        inputs.property("apiGateway", apiGateway)
        inputs.property("signSalt", signSalt)
        inputs.property("apiCid", apiCid)
        outputs.dir(generatedDir)
        doLast {
            // Kotlin 字符串字面量转义：保证网关/盐值中的 $、"、\ 不破坏生成代码。
            // 注意：必须是 doLast 内的局部函数——配置缓存禁止 task action 引用脚本级对象。
            fun escapeKotlinStringLiteral(raw: String): String =
                raw
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("$", "\\$")

            val packageDir = generatedDir.get().dir("com/github/ilife798/buildConfig").asFile
            packageDir.mkdirs()
            packageDir.resolve("IosBuildConfig.kt").writeText(
                """
                |package com.github.ilife798.buildConfig
                |
                |// 由 Gradle 任务 generateIosBuildConfig 生成，勿手动修改，不入库。
                |object IosBuildConfig {
                |    const val API_GATEWAY: String = "${escapeKotlinStringLiteral(apiGateway)}"
                |    const val SIGN_SALT: String = "${escapeKotlinStringLiteral(signSalt)}"
                |    const val API_CID: String = "${escapeKotlinStringLiteral(apiCid)}"
                |}
                """.trimMargin(),
            )
        }
    }

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.github.ilife798.shared"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    iosArm64()
    iosSimulatorArm64()

    targets
        .withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
        .configureEach {
            binaries.framework {
                baseName = "shared"
            }
        }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
            implementation(libs.androidx.activity.compose)
            implementation(libs.alipay.sdk)
            implementation(libs.wechat.sdk)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.zxing.core)
        }
        iosMain.dependencies {
            implementation(libs.ktor.darwin)
        }
        iosMain {
            // 直接引用任务 provider：Gradle 自动建立编译任务对生成任务的依赖。
            kotlin.srcDir(generateIosBuildConfig)
        }
        commonMain.dependencies {
            @Suppress("DEPRECATION")
            implementation("org.jetbrains.compose.components:components-resources:${libs.versions.composeMultiplatform.get()}")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.navigation)
            implementation(libs.miuix.blur)
            implementation(libs.miuix.shader)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.ktor.core)
            implementation(libs.ktorContentNegotiation)
            implementation(libs.ktorSerializationJson)
            implementation(libs.kotlinxSerializationJson)
            implementation(libs.aboutlibraries.compose.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// 依赖变化时自动重新生成开源许可清单，供 Compose Resources 读取
tasks
    .matching {
        it.name in
            setOf(
                "prepareComposeResourcesTaskForCommonMain",
                "copyNonXmlValueResourcesForCommonMain",
                "convertXmlValueResourcesForCommonMain",
            )
    }.configureEach {
        dependsOn("exportLibraryDefinitions")
    }
