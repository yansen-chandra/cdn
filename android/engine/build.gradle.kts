// Pure Kotlin/JVM: the converter engine, shared by the app and tested against
// the Python reference (converter/golden.json).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

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
    // Android ships org.json; plain JVM (tests) needs the library.
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    val converterDir = providers.gradleProperty("converterDir").orElse(rootProject.file("../converter").absolutePath)
    systemProperty("converter.dir", converterDir.get())
}
