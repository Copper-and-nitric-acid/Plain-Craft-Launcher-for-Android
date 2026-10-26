// :pcla-core —— 挂进 FCL fork 的决策模块。
//
// 刻意不依赖 Android、不依赖任何第三方库：FCL 的 app 模块只需要传进来
// 几个字符串（SoC、GPU、内存），拿回堆大小/渲染距离/JVM 参数。
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // 一个都不需要。MiniJson 是手写的，就是为了这个。
}
