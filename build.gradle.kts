plugins {
    // Android lint/analysis currently expects Kotlin metadata 2.0.x; keep Kotlin aligned so FV-01 runs are clean.
    id("org.jetbrains.kotlin.jvm") version "2.0.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0" apply false

    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
}
