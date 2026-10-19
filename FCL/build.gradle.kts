import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.serialization)
    id("checkstyle")
}

checkstyle {
    // 规则集 config/checkstyle/checkstyle.xml 与 Android Studio 默认格式对齐，仅检查 Java 代码
    toolVersion = "10.12.5"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
}

// AGP 不提供 Java 插件的 SourceSetContainer，checkstyle 插件不会自动创建任务，
// 因此手动注册 checkstyle 任务，检查范围为主源码目录的 Java 文件
tasks.register<Checkstyle>("checkstyle") {
    description = "Run checkstyle on the FCL Java sources."
    group = "verification"
    source(layout.projectDirectory.dir("src/main/java"))
    include("**/*.java")
    classpath = files()
    maxErrors = 0
    maxWarnings = 0
}

android {
    namespace = "com.dsh.fcl.androidlauncher"
    compileSdk = libs.versions.compileSdk.get().toInt()

    var localProperty: Properties? = null
    if (file("${rootDir}/local.properties").exists()) {
        localProperty = Properties()
        file("${rootDir}/local.properties").inputStream().use { localProperty.load(it) }
    }
    val pwd = System.getenv("FCL_KEYSTORE_PASSWORD") ?: localProperty?.getProperty("pwd")
    val curseApiKey = System.getenv("CURSE_API_KEY") ?: localProperty?.getProperty("curse.api.key")
    val oauthApiKey = System.getenv("OAUTH_API_KEY") ?: localProperty?.getProperty("oauth.api.key")
    // ★ T-D1 提前落地（2026-10-19，锐评"签名信任模型破产"整改）：出货签名密钥。
    //   与 FCL 上游同 blob 的公开 debug-key.jks 不再用于出货（谁都能签同包名顶替更新）；
    //   新私钥 release-key/dsh-release.jks 两仓均排除、只在本机与备份里（密码在
    //   local.properties 的 dsh.pwd，该文件同样不入库）。环境变量 DSH_KEYSTORE_PASSWORD
    //   优先（CI/换机场景），缺失时**只为 release 变体**报错（fordebug 调试包不受影响）。
    val dshPwd = System.getenv("DSH_KEYSTORE_PASSWORD") ?: localProperty?.getProperty("dsh.pwd")
    // 版本号单一来源：根 gradle.properties 的 dshVersion / dshVersionCode
    // 缺了就直接报错，而不是悄悄用一个空版本号打包。
    val dshVersion = providers.gradleProperty("dshVersion").orNull
        ?: error("缺少 dshVersion：请在根 gradle.properties 里设置（版本号单一来源）")
    val dshVersionCode = providers.gradleProperty("dshVersionCode").orNull?.toIntOrNull()
        ?: error("缺少 dshVersionCode（整数）：请在根 gradle.properties 里设置")
    // ★ 显示用 fix 标签（R16/fix4 起）：正式包留空；fix 包为 fixN（如 fix4）。
    //   拼进 versionName 尾部 —— 关于页 / 系统「应用信息」/ APK 文件名全链可见
    //   （此前它们只有 dshVersion，看不出装的是第几轮修复）。
    //   dshVersion 本身保持不变（fix 包约定），本标签只影响**显示**。
    val dshFixLabel = providers.gradleProperty("dshFixLabel").orNull?.trim().orEmpty()
    val dshVersionName = if (dshFixLabel.isEmpty()) dshVersion else "$dshVersion-$dshFixLabel"
    // 命令行 -Darch 优先；local.properties 仅在命令行未指定时生效
    if (System.getProperty("arch") == null && localProperty != null && localProperty.getProperty(
            "arch",
            "all"
        ) == "arm64"
    )
        System.setProperty("arch", "arm64")

    signingConfigs {
        create("FCLKey") {
            storeFile = file("../key-store.jks")
            storePassword = pwd
            keyAlias = "FCL-Key"
            keyPassword = pwd
        }
        create("FCLDebugKey") {
            storeFile = file("../debug-key.jks")
            storePassword = "FCL-Debug"
            keyAlias = "FCL-Debug"
            keyPassword = "FCL-Debug"
        }
        // ★ 出货签名（T-D1）：新私钥，两仓都排除（见 .git/info/exclude 与 ws-tools 的排除清单）。
        //   密钥缺失（另一台机器/手机沙箱）→ 此配置为空，只有 release 变体会在**签名阶段**
        //   以清晰错误失败；fordebug 调试包与纯编译校验不受影响（不读这把钥匙）。
        //   密码缺失同理（storePassword=null → 签名阶段报 missing property，不挡其它变体）。
        create("DshReleaseKey") {
            val dshJks = file("../release-key/dsh-release.jks")
            if (dshJks.exists()) {
                storeFile = dshJks
                storePassword = dshPwd
                keyAlias = "dsh-release"
                keyPassword = dshPwd
            }
        }
    }

    defaultConfig {
        applicationId = "com.dsh.fcl.androidlauncher"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = dshVersionCode
        // R16/fix4：versionName = dshVersion（+ "-fixN"，若 dshFixLabel 非空）——
        // 见上方 dshVersionName 的注释。此前这里直接是 dshVersion，关于页看不出 fix 轮次。
        versionName = dshVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 方案 B：PtyNative 负责在 Android 进程内创建 PTY；proot/loader/busybox
    // 以预编译 arm64 jniLibs 随 APK 提供，不在这里重新编译 proot。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/ptyjni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testBuildType = "fordebug"

    buildTypes {
        // ★ T-D1（2026-10-19 提前落地）：**release = 出货变体**。
        //   与旧形态的区别：正式包名（无 .debug 后缀）、非 debuggable（AGP 的 release
        //   默认就是 false，此前出货走的是 fordebug 才带 debuggable）、新密钥签名。
        //   注意：首次用本变体出货 = 包名与签名都变 —— 已装 .debug 验证包的用户需要
        //   卸载重装（数据不迁移，这是 DEV-PLAN 决策 1 已知的代价，提前到 0.1.6 承受）。
        getByName("release") {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("DshReleaseKey")
        }
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("FCLKey")
        }
        // ★ fordebug：保留为**内部调试通道**（此前它承担出货；T-D1 后出货走 release）。
        //   价值：debuggable + run-as，抓日志/查 app 私有目录（tools/ondevice.md 的手册
        //   依赖它）。包名带 .debug 后缀 → 与出货包可**并存安装**，互不顶替。
        create("fordebug") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("FCLDebugKey")
        }
        configureEach {
            resValue("string", "app_version", defaultConfig.versionName.toString())
            resValue("string", "curse_api_key", curseApiKey.toString())
            resValue("string", "oauth_api_key", oauthApiKey.toString())
        }
    }



    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // core library desugaring：java.time / java.util.stream / Optional 等脱糖到 minSdk 26 可用
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // rootfs.tar.xz 已是压缩包（300MB），不要让 aapt 再 deflate 一遍：
    // 二次压缩既慢又几乎不减小体积，还会让打包阶段明显变长。
    androidResources {
        noCompress += listOf("xz")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    splits {
        val arch = System.getProperty("arch", "all")
        if (arch != "all") {
            abi {
                isEnable = true
                reset()
                when (arch) {
                    "arm" -> include("armeabi-v7a")
                    "arm64" -> include("arm64-v8a")
                    "x86" -> include("x86")
                    "x86_64" -> include("x86_64")
                }
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                (output.getFilter(ABI)?.identifier ?: "all").let { abi ->
                    output.outputFileName =
                        "dsh-fcl-android-launcher-${project.android.defaultConfig.versionName}-${abi}.apk"
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(project(":ZipFileSystem"))
    implementation(libs.commons.io)
    implementation(libs.commons.compress)
    implementation(libs.chardet)
    implementation(libs.xz)
    implementation(libs.gson)
    implementation(libs.junrar)
    implementation(libs.appcompat)
    implementation(libs.androidx.viewpager2)
    implementation(libs.core.splashscreen)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.recyclerview)
    implementation(libs.coroutines.android)
    implementation(libs.glide)
    implementation(libs.datastore)
    implementation(libs.kotlinx.serialization.json)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}