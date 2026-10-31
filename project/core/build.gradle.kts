plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

// The core module deliberately depends on neither Android nor any third-party library.
// That lets it build with bare kotlinc in Termux, pass CI instantly, and run self-checks on desktop.
dependencies {
    testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "跑 core 的自检（无需 JUnit）"
    mainClass.set("io.pcla.core.SelfTestKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(rootProject.file("device_profiles.json").absolutePath)
}
