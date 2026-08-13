plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin — NO Android dependencies. Calculators live here so the nutrition
// maths, TDEE formulas, 1RM estimation and PR detection are JVM-unit-testable
// without an emulator (PHASE2_ARCHITECTURE.md §3).
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
    api(project(":core:model"))
    testImplementation(kotlin("test"))
}
