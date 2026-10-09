import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 默认关闭；本地包按需注入，真实值只来自未提交的文件或环境变量。
val bundleDefaultApiKey = providers.gradleProperty("bundleDefaultApiKey").orNull == "true"
val privateApiProperties = Properties().apply {
    if (bundleDefaultApiKey) rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val bundledApiKey = if (bundleDefaultApiKey)
    providers.environmentVariable("DEEPSEEK_API_KEY").orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: privateApiProperties.getProperty("DEEPSEEK_API_KEY", "").trim()
    else ""
require(!bundleDefaultApiKey || bundledApiKey.isNotEmpty()) { "本地默认 API Key 未配置" }
val bundledApiKeyLiteral = "\"" + bundledApiKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
    namespace = "com.jiligulu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jiligulu.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "1.1.0"
        buildConfigField("String", "DEFAULT_DEEPSEEK_API_KEY", bundledApiKeyLiteral)

    }

    /**
     * 正式包签名。
     *
     * 签名信息放在**用户级** `~/.gradle/gradle.properties`（JILIGULU_* 四个属性），
     * keystore 本身在项目根 `jiligulu-release.jks`——两者都已被 .gitignore 覆盖，
     * 不会进仓库。
     *
     * 属性缺失时**回退到 debug 签名**：这样别人 clone 下来也能构建，
     * 不至于因为拿不到密钥就整个项目跑不起来（产物不能用于正式分发是另一回事）。
     */
    signingConfigs {
        create("release") {
            val storePath = providers.gradleProperty("JILIGULU_STORE_FILE").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("JILIGULU_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("JILIGULU_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("JILIGULU_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // 关掉 R8 是为了保住 Compose 的反射路径，但 release 变体本身就比 debug 快得多：
            // debug 包带着 Compose 的调试开销与无优化字节码，卡顿主要来自这里。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 保持既有发行包的签名，以便覆盖安装并保留本机数据。
            // 修改签名需要独立迁移安排，不能在本次版本更新中直接切换。
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        jniLibs {
            // Android 10+ requires executing bundled engines from the installer's library directory.
            useLegacyPackaging = true
            // Preserve the verified upstream executable, which is a standalone ELF, not JNI code.
            keepDebugSymbols += "**/libpikafish.so"
            keepDebugSymbols += "**/librapfi.so"
        }
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
    // 1.8.0 fixes the test dispatcher interceptor key when a real Flow switches to I/O.
    // Keep this harness correction separate from the production Compose BOM.
    testImplementation("androidx.compose.ui:ui-test-junit4:1.8.0")
    testImplementation("androidx.compose.ui:ui-test:1.8.0")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    // Debug compilation must match the corrected UI-test runtime (FlowRow ABI changed in 1.8).
    // This platform applies only to debug; release keeps the catalog's production BOM.
    debugImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
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
