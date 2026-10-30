import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    `java-library`
}

group = "io.github.manamiproject"
version = project.findProperty("release.version") as String? ?: ""

val kotlinVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4 // most recent stable kotlin version for language and std lib

repositories {
    mavenCentral()
}

dependencies {
    api(libs.kotlin.stdlib)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

kotlin {
    explicitApi()
    jvmToolchain(JavaVersion.VERSION_25.toString().toInt())
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        apiVersion.set(kotlinVersion)
        languageVersion.set(kotlinVersion)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // zstd-jni loads a native library, which JDK 24+ only allows silently with this flag.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    reports.html.required.set(false)
    reports.junitXml.required.set(true)
    maxParallelForks = Runtime.getRuntime().availableProcessors()
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

val javaDoc = tasks.register<Jar>("javaDoc") {
    archiveClassifier.set("javadoc")
    from(sourceSets.main.get().allSource)
}


fun parameter(name: String, default: String = ""): String {
    val env = System.getenv(name) ?: ""
    if (env.isNotBlank()) {
        return env
    }

    val property = project.findProperty(name) as String? ?: ""
    if (property.isNotEmpty()) {
        return property
    }

    return default
}