import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 内置 API Key 从 local.properties 读取（该文件已被 .gitignore 排除，不会进仓库）。
// 也可用环境变量 DEEPSEEK_API_KEY 覆盖，方便 CI。两者都缺省时注入空串。
val deepSeekApiKey: String = run {
    val fromEnv = System.getenv("DEEPSEEK_API_KEY").orEmpty()
    val raw = if (fromEnv.isNotBlank()) fromEnv else {
        val propsFile = rootProject.file("local.properties")
        if (propsFile.exists()) {
            Properties().apply { propsFile.inputStream().use { load(it) } }
                .getProperty("DEEPSEEK_API_KEY").orEmpty()
        } else ""
    }
    raw.trim()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "").replace("\r", "")
}

android {
    namespace = "com.jiligulu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jiligulu.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.2"

        // 注入到 BuildConfig.DEEPSEEK_API_KEY，由 AiConfig.DEFAULT_API_KEY 读取
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepSeekApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
    sourceSets.getByName("test").resources.srcDir("$projectDir/schemas")
}

// Room schema 导出到 app/schemas，方便以后做数据库迁移对比
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.squareup.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
}
