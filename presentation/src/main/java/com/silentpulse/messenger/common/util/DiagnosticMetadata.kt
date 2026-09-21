package com.silentpulse.messenger.common.util

internal object DiagnosticMetadata {
    fun describe(throwable: Throwable?, stack: Array<StackTraceElement> = Thread.currentThread().stackTrace): String {
        val caller = stack.firstOrNull {
            (it.className.startsWith("com.silentpulse.") ||
                it.className.startsWith("com.android.mms.") ||
                it.className.startsWith("com.klinker.")) &&
                it.className != javaClass.name &&
                it.className != FileLoggingTree::class.java.name &&
                it.className != MetadataDebugTree::class.java.name
        }
        val source = caller?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
        return "diagnostic source=$source type=${throwable?.javaClass?.simpleName ?: "none"}"
    }
}
