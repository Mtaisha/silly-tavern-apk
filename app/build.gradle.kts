import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建时传入递增的版本号（构建时间的「年月日时」，GitHub Actions 和本地构建一样），保证每次新包都能直接覆盖安装。
val ciVersionCode = (System.getenv("VERSION_CODE") ?: "").toIntOrNull() ?: 1
val ciVersionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.$ciVersionCode"

android {
    namespace = "io.github.taverndroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jiuguan"
        minSdk = 29
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = ciVersionName
        ndk { abiFilters += listOf("arm64-v8a") }
        val stBundle = rootProject.file("app/src/main/assets/st-bundle.version")
        buildConfigField("String", "ST_BUNDLE", "\"${if (stBundle.exists()) stBundle.readText().trim() else "missing"}\"")
    }

    signingConfigs {
        // 固定签名：仓库根目录有 keystore.properties 就用它指向的钥匙签（钥匙和这个文件都不进 git，见 README「自己构建」），
        // 没有就用调试签名。debug 和 release 用同一把钥匙，换哪种构建都能覆盖安装。
        val props = rootProject.file("keystore.properties")
        if (props.exists()) {
            val p = Properties().apply { props.reader(Charsets.UTF_8).use { load(it) } } // 按 UTF-8 读，密码里有中文也不乱
            create("fixed") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        val fixed = signingConfigs.findByName("fixed")
        debug {
            if (fixed != null) signingConfig = fixed
        }
        release {
            isMinifyEnabled = false
            signingConfig = fixed ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs {
            // Node.js and git are executables: they must be extracted to nativeLibraryDir
            // (the only app location Android allows exec() from) and left untouched.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    androidResources {
        // The bundle is already a zip; storing it uncompressed lets the installer stream it with a known length.
        noCompress += listOf("zip")
    }

    lint {
        // lint 的警告不拦构建。
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
