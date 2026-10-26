plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    // 整个工程里唯一一个第三方依赖。放在独立模块，core 保持零依赖。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("downloadSelfTest") {
    group = "verification"
    description = "跑下载层纯逻辑自检（不联网）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.pcla.core.download.DownloadSelfTestKt")
}
