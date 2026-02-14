plugins {
    // Keep Kotlin plugins on a single version to avoid mixed-toolchain resolution issues.
    id("org.jetbrains.kotlin.jvm") version "2.0.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0" apply false

    // For building a single runnable server fat jar (`:server:shadowJar` -> `server-all.jar`).
    // NOTE: The legacy Shadow plugin ID (`com.github.johnrengelman.shadow`) is unmaintained and not compatible
    // with newer Gradle versions (e.g., 8.7). GradleUp is the current maintainer.
    id("com.gradleup.shadow") version "8.3.7" apply false

    // AGP 8.5.2 supports Gradle 8.7+, which avoids IDE failures when wrapper settings are ignored.
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
}
