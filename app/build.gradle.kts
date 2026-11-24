import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Sentry DSN 构建时注入，不写死在代码里：
// 本地构建：gradle assembleDebug -PsentryDsn=https://...@o....ingest.us.sentry.io/...
// GitHub Actions：工作流里加 -PsentryDsn=${{ secrets.SENTRY_DSN }}（见 README 的 Sentry 一节）。
// 不传时 DSN 为空字符串，Sentry 不初始化，App 照常用。
val sentryDsn: String = providers.gradleProperty("sentryDsn")
    .orElse(providers.environmentVariable("SENTRY_DSN")).getOrElse("")

android {
    namespace = "com.maihe.chengguang"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maihe.chengguang"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.2.7"
        // 注意：AGP 8.x 的 Kotlin DSL 里 buildConfigField 只能写在 defaultConfig /
        // buildTypes / productFlavors 里，直接写在 android {} 下会报
        // "Unresolved reference: buildConfigField"（脚本编译错误）。
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // 启动图标 PNG 由 decodeLauncherIcons 任务在构建时生成到这里
            res.srcDirs("build/generated/icons/res")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Pause 图标不在 material-icons-core 里（只有 Home/List/Person/PlayArrow 等 58 个），
    // 必须加 extended，否则 Icons.Default.Pause 编译报错
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    // 报错收集 Sentry（组织 maihe，项目 chengguang-android 待建）：
    // 只发报错堆栈，beforeSend 里脱敏（见 ErrorReporting.kt）；DSN 为空时不初始化。
    implementation("io.sentry:sentry-android:8.65.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// 启动图标：仓库里只存两张 512px 原图的 base64 文本（push_files 不接受二进制文件），
// 构建时由本任务解码并生成各密度的 mipmap / 自适应图标前景 PNG。
// 白底版是用户选定的图标（chengguang-icon-2048-white.png）；
// 自适应前景用透明底版，圆形控制在 66dp 安全区内，白底由 @color/chengguang_white 提供，
// 这样圆形在任何形状的启动器遮罩下都不会被裁切。
val decodeLauncherIcons by tasks.registering {
    val iconSources = file("icon-sources")
    val outRes = layout.buildDirectory.dir("generated/icons/res")
    inputs.files(
        iconSources.resolve("icon-white-512.b64"),
        iconSources.resolve("icon-transparent-512.b64")
    )
    outputs.dir(outRes)
    doLast {
        fun decode(name: String): BufferedImage {
            val bytes = Base64.getDecoder().decode(iconSources.resolve(name).readText().trim())
            return ImageIO.read(ByteArrayInputStream(bytes)) ?: error("无法解码 $name")
        }
        fun scale(src: BufferedImage, w: Int, h: Int): BufferedImage {
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, w, h, null)
            g.dispose()
            return out
        }
        val white = decode("icon-white-512.b64")
        val transparent = decode("icon-transparent-512.b64")
        val outDir = outRes.get().asFile
        // 传统 mipmap：用用户选定的白底整图
        mapOf(
            "mipmap-mdpi" to 48,
            "mipmap-hdpi" to 72,
            "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144,
            "mipmap-xxxhdpi" to 192
        ).forEach { (dir, size) ->
            val d = File(outDir, dir).apply { mkdirs() }
            ImageIO.write(scale(white, size, size), "png", File(d, "ic_launcher.png"))
            ImageIO.write(scale(white, size, size), "png", File(d, "ic_launcher_round.png"))
        }
        // 自适应前景：透明底圆形缩放到 66dp 安全区，居中放在 108dp 画布上
        mapOf(
            "drawable-mdpi" to 108,
            "drawable-hdpi" to 162,
            "drawable-xhdpi" to 216,
            "drawable-xxhdpi" to 324,
            "drawable-xxxhdpi" to 432
        ).forEach { (dir, canvas) ->
            val logoSize = canvas * 66 / 108
            val d = File(outDir, dir).apply { mkdirs() }
            val out = BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            g.drawImage(scale(transparent, logoSize, logoSize), (canvas - logoSize) / 2, (canvas - logoSize) / 2, null)
            g.dispose()
            ImageIO.write(out, "png", File(d, "ic_launcher_foreground.png"))
        }
    }
}
tasks.named("preBuild") { dependsOn(decodeLauncherIcons) }
