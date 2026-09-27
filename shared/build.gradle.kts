import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.multiplatform)
}

val appVersion = libs.versions.podor.get()
val channelFile = rootProject.file("release/channel.properties")
val generatedBuildInfo = layout.buildDirectory.dir("generated/buildInfo")
val generateBuildInfo = tasks.register("generateBuildInfo") {
    inputs.property("version", appVersion)
    inputs.file(channelFile)
    outputs.dir(generatedBuildInfo)
    doLast {
        val channel = Properties().apply { channelFile.inputStream().use { load(it) } }
        val url = channel.getProperty("manifestUrl", "")
        require(url.isEmpty() || Regex("https://[A-Za-z0-9._~:/?=&%+-]+").matches(url))
        val output = generatedBuildInfo.get().file("app/podor/domain/AppBuildInfo.kt").asFile
        output.parentFile.mkdirs()
        output.writeText("""
            package app.podor.domain

            object AppBuildInfo {
                const val version = "$appVersion"
                const val manifestUrl = "$url"
            }
        """.trimIndent())
    }
}

kotlin {
    jvm()
    android {
        namespace = "app.podor.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }
    if (System.getProperty("os.name").contains("Mac")) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.compilations.getByName("main").cinterops.create("podor") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/podor.def"))
                includeDirs(rootProject.file("engine/include"))
            }
            target.binaries.framework {
                baseName = "PodorShared"
                isStatic = true
                val rustTarget =
                    if (target.name == "iosArm64") "aarch64-apple-ios" else "aarch64-apple-ios-sim"
                linkerOpts(
                    rootProject.file("target/$rustTarget/release/libpodor_engine.a").absolutePath
                )
            }
        }
    }
    sourceSets {
        commonMain { kotlin.srcDir(files(generatedBuildInfo).builtBy(generateBuildInfo)) }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmMain {
            kotlin.srcDir("src/jvmBridgeMain/kotlin")
            dependencies { implementation(compose.desktop.currentOs) }
        }
        getByName("androidMain").kotlin.srcDir("src/jvmBridgeMain/kotlin")
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "app.podor.resources"
}
