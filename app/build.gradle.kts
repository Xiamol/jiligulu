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
        versionCode = 10
        versionName = "0.6.0"

        // 注入到 BuildConfig.DEEPSEEK_API_KEY，由 AiConfig.DEFAULT_API_KEY 读取
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepSeekApiKey\"")
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
            // 自用测试阶段刻意用 **debug 签名**：Android 不允许不同签名的包互相覆盖，
            // 用正式签名会导致必须卸载旧包、账单数据全丢。改用 debug 签名后可以**直接覆盖安装**，
            // 该有的性能收益（非 debuggable + 优化字节码）一点不少。
            // 将来要对外正式发布时，把下面这行换成 signingConfigs.getByName("release")。
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
