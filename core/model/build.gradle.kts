plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin — NO Android dependencies. This is what makes the nutrition maths,
// TDEE formulas and unit conversions JVM-unit-testable (PHASE2_ARCHITECTURE.md §3).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}
