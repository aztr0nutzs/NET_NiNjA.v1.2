plugins {
    // Android lint/analysis currently expects Kotlin metadata 2.0.x; keep Kotlin aligned so FV-01 runs are clean.
    id("org.jetbrains.kotlin.jvm") version "2.0.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0" apply false

    // For building a single runnable server fat jar (`:server:shadowJar` -> `server-all.jar`).
    // NOTE: The legacy Shadow plugin ID (`com.github.johnrengelman.shadow`) is unmaintained and not compatible
    // with newer Gradle versions (e.g., 8.7). GradleUp is the current maintainer.
    id("com.gradleup.shadow") version "8.3.7" apply false

    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
}
