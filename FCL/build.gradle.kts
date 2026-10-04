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
    }

    defaultConfig {
        applicationId = "com.dsh.fcl.androidlauncher"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 101
        versionName = "0.1.1-SNAPSHOT"
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
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("FCLKey")
        }
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("FCLKey")
        }
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