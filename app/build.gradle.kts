import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.aiexercise"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.aiexercise"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 本地注入（local.properties 已在 .gitignore，Key 不进仓库）
        buildConfigField("String", "DEFAULT_LLM_BASE_URL", "\"${localProperties.getProperty("llm.baseUrl", "https://discovery-api.intern-ai.org.cn/v1")}\"")
        buildConfigField("String", "DEFAULT_LLM_MODEL", "\"${localProperties.getProperty("llm.model", "intern-s2")}\"")
        buildConfigField("String", "DEFAULT_LLM_API_KEY", "\"${localProperties.getProperty("llm.apiKey", "")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // 本地 OCR：ML Kit 中文文字识别（模型打包进 APK，离线可用）
    implementation(libs.mlkit.text.recognition.chinese)
    // 多页文档扫描（自动透视矫正 + 增强 + 多页），无 Play 服务时由本地增强回退
    implementation(libs.mlkit.document.scanner)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // 数据持久化 / AI JSON 契约
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
