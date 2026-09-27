import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.resources)
    implementation(libs.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.serialization.json)
}

val windows = System.getProperty("os.name").startsWith("Windows")
val localCargo = rootProject.file(".tools/cargo/bin/cargo.exe")
val buildEngine =
    tasks.register<Exec>("buildEngine") {
        workingDir(rootDir)
        executable = if (localCargo.exists()) localCargo.absolutePath else "cargo"
        args("build", "--release", "--locked")
        if (localCargo.exists()) {
            environment("CARGO_HOME", rootProject.file(".tools/cargo").absolutePath)
            environment("RUSTUP_HOME", rootProject.file(".tools/rustup").absolutePath)
            environment("RUSTFLAGS", "-C linker=rust-lld -C link-self-contained=yes")
            environment("PATH", "${localCargo.parent};${System.getenv("PATH")}")
        }
        inputs.files(
            rootProject.fileTree("engine") { include("src/**", "Cargo.toml") },
            rootProject.file("Cargo.lock"),
        )
        outputs.file(
            rootProject.file(
                "target/release/${if (windows) "podor_engine.dll" else if (System.getProperty("os.name").contains("Mac")) "libpodor_engine.dylib" else "libpodor_engine.so"}"
            )
        )
    }

tasks.processResources {
    dependsOn(buildEngine)
    from(rootProject.file("target/release")) {
        include("podor_engine.dll", "libpodor_engine.dylib", "libpodor_engine.so")
        into("native")
    }
    from(rootProject.file("licenses")) { into("licenses") }
}

compose.desktop {
    application {
        mainClass = "app.podor.desktop.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        nativeDistributions {
            outputBaseDir.set(
                project.layout.buildDirectory.dir("release/${libs.versions.podor.get()}")
            )
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            windows {
                iconFile.set(project.file("icons/podor.ico"))
                upgradeUuid = "b96b48f7-6fe7-3d0b-9c36-0f9c2d8c9eac"
                dirChooser = true
                menuGroup = "podor"
            }
            vendor = "podor"
            packageName = "podor"
            packageVersion = libs.versions.podor.get()
            modules("java.desktop", "jdk.unsupported")
        }
    }
}

tasks.test { jvmArgs("--enable-native-access=ALL-UNNAMED") }
