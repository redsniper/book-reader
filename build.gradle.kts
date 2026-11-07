// 顶层构建脚本：仅声明插件版本，具体模块在 app/build.gradle.kts 中应用。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
