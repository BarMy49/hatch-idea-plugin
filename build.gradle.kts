import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}
group = "dev.barmy"
version = "0.6.5"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
dependencies {
    implementation("org.tomlj:tomlj:1.1.1")
    intellijPlatform {
        bundledPlugin("PythonCore")
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde) else pycharm(providers.gradleProperty("platformVersion").getOrElse("2025.3"))
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
intellijPlatform {
    pluginVerification {
        ides {
            current()
            providers.gradleProperty("verificationIdePath").orNull?.let { local(file(it)) }
        }
    }
    pluginConfiguration {
        id = "dev.barmy.hatch.tasks"
        name = "Hatch Tasks"
        version = project.version.toString()
        description = "Hatch environments, scripts, matrix tasks and Python package builds in PyCharm."
        ideaVersion { sinceBuild = "253"; untilBuild = "262.*" }
    }
}
tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    // These are unit/Swing action tests, not IntelliJ application fixtures.
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
}
