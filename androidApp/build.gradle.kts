plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.podor.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "app.podor"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = libs.versions.podor.get()
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }
    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")
    sourceSets["main"].res.srcDir("../shared/src/commonMain/composeResources")
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.compose.runtime)
}

val verifyNativeLibraries =
    tasks.register("verifyNativeLibraries") {
        doLast {
            for (abi in listOf("arm64-v8a", "x86_64")) {
                check(file("src/main/jniLibs/$abi/libpodor_engine.so").isFile) {
                    "缺少 $abi 绘图引擎，请先运行 scripts/build-android.ps1"
                }
            }
        }
    }

tasks.named("preBuild") { dependsOn(verifyNativeLibraries) }
