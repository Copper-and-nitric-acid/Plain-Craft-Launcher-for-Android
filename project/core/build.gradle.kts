plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

// core 模块刻意不依赖 Android，也不依赖任何第三方库。
// 这样它在 Termux 里能用裸 kotlinc 编译，在 CI 上能秒过，在桌面能跑自检。
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
