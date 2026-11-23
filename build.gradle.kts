// 顶层构建文件
// Kotlin 2.0 起 Compose 编译器改由 kotlin("plugin.compose") 插件提供，
// 不再用 composeOptions.kotlinCompilerExtensionVersion 指定版本。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
