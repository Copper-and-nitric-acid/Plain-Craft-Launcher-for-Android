package com.oracle.dalvik;

/**
 * Pojav's in-process JVM entry: the symbol in libpojavexec.so is
 * `Java_com_oracle_dalvik_VMLauncher_launchJVM` (see the symbol table in docs/ARCHITECTURE.md).
 *
 * It must be a Java file, not Kotlin: Kotlin's object/companion generates a JVM method name
 * that does not match the symbol (it becomes an instance method plus a Companion class), so
 * native fails with `UnsatisfiedLinkError: No implementation found for ...`.
 */
public final class VMLauncher {
    private VMLauncher() {}

    /** Arguments are JVM args + main class + game args — the same list that follows `java` on the command line. */
    public static native int launchJVM(String[] args);
}
