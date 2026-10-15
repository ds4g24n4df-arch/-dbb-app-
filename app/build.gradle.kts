import java.security.SecureRandom
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// App 标识和签名钥匙第一次编译时生成, 之后一直用同一份: 手机只让同标识、同钥匙签的新版盖掉旧版,
// 换了任何一样都得先卸载旧版, 手机上存的数据跟着没了. 两样都要跟着工程走, 不能放进 .gitignore.
val random = SecureRandom()

fun randomText(length: Int, alphabet: String = "abcdefghijklmnopqrstuvwxyz0123456789"): String =
    (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

val appPropertiesFile = rootProject.file("app.properties")
val appProperties = Properties().apply { appPropertiesFile.reader(Charsets.UTF_8).use { load(it) } }
if (appProperties.getProperty("applicationId").isNullOrBlank()) {
    val generated = "cc.luxiaofei.u" + randomText(10)
    val text = appPropertiesFile.readText(Charsets.UTF_8)
    appPropertiesFile.writeText(text.replace(Regex("(?m)^applicationId=.*$"), "applicationId=$generated"), Charsets.UTF_8)
    appProperties.setProperty("applicationId", generated)
}
val applicationIdValue: String = appProperties.getProperty("applicationId")

val signingDir = rootProject.file("signing")
val keystorePropertiesFile = signingDir.resolve("keystore.properties")
if (!keystorePropertiesFile.exists()) {
    val password = randomText(24)
    signingDir.mkdirs()
    providers.exec {
        commandLine(
            File(System.getProperty("java.home"), "bin/keytool").path,
            "-genkeypair", "-keystore", signingDir.resolve("app.jks").path, "-storetype", "PKCS12",
            "-alias", "app", "-keyalg", "RSA", "-keysize", "2048", "-validity", "36500",
            "-storepass", password, "-keypass", password, "-dname", "CN=$applicationIdValue",
        )
    }.result.get().assertNormalExitValue()
    keystorePropertiesFile.writeText("storeFile=app.jks\nstorePassword=$password\nkeyAlias=app\nkeyPassword=$password\n")
}
val keystoreProperties = Properties().apply { keystorePropertiesFile.inputStream().use { load(it) } }

// 接口服务的地址, 只有带接口服务 (工程根有 compose.yaml) 的 App 用得上:
// 调试包连这台电脑上开发用的接口服务 (模拟器里 10.0.2.2 就是这台电脑, 端口由平台以 API_PORT 给),
// 正式包连发布后的地址 (app.properties 的 apiHost). 缺了哪个就在编译那一步拦下, 不编出连不上的包.
val hasApiServer = rootProject.file("compose.yaml").exists()
val devApiPort: String? = providers.environmentVariable("API_PORT").orNull
val apiHost: String = appProperties.getProperty("apiHost").orEmpty().trim().removePrefix("https://").trimEnd('/')
val missingDevApi = hasApiServer && devApiPort.isNullOrBlank()
val missingApiHost = hasApiServer && apiHost.isEmpty()

android {
    namespace = "cc.luxiaofei.app"
    compileSdk = 37

    defaultConfig {
        applicationId = applicationIdValue
        minSdk = 26
        targetSdk = 37
        versionCode = appProperties.getProperty("versionCode").toInt()
        versionName = appProperties.getProperty("versionName")
        resValue("string", "app_name", appProperties.getProperty("appName"))
    }

    signingConfigs {
        create("app") {
            storeFile = signingDir.resolve(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("app")
            buildConfigField("String", "API_BASE", "\"${if (devApiPort.isNullOrBlank()) "" else "http://10.0.2.2:$devApiPort"}\"")
        }
        release {
            signingConfig = signingConfigs.getByName("app")
            buildConfigField("String", "API_BASE", "\"${if (apiHost.isEmpty()) "" else "https://$apiHost"}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }
}

val checkDevApi by tasks.registering {
    val missing = missingDevApi
    doLast {
        if (missing) throw GradleException("接口服务还没起: 先用 shell 起 service=api 的接口服务 (见 server/AGENTS.md), 再编调试包")
    }
}
val checkApiHost by tasks.registering {
    val missing = missingApiHost
    doLast {
        if (missing) throw GradleException("正式包还不知道连哪: 先 luffy deploy 发布接口服务, 把打出的地址填进 app.properties 的 apiHost, 再编正式包")
    }
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(checkDevApi) }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkApiHost) }

kotlin {
    jvmToolchain(17)
}

room {
    // 表结构的历史版本存在这里, 改表时按它自动生成迁移, 用户手机上已有的数据不丢
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
}
