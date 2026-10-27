package com.oracle.dalvik;

/**
 * Pojav 的同进程 JVM 入口：符号名在 libpojavexec.so 里是
 * `Java_com_oracle_dalvik_VMLauncher_launchJVM`（见 docs/ARCHITECTURE.md 的符号表）。
 *
 * 必须是 Java 文件而不是 Kotlin：Kotlin 的 object/companion 生成的 JVM 方法名
 * 与符号对不上（会变成 Instance 方法 + Companion 类），native 找不到就报
 * `UnsatisfiedLinkError: No implementation found for ...`。
 */
public final class VMLauncher {
    private VMLauncher() {}

    /** 参数是 JVM 参数 + 主类 + 游戏参数，与命令行 `java` 后面那串一致。 */
    public static native int launchJVM(String[] args);
}
