plugins {
    val kotlinVersion = "2.0.0"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.15.0"
    id("me.him188.maven-central-publish") version "1.0.0-dev-3"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "top.colter"
version = "3.2.16-BETA1"

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

mavenCentralPublish {
    useCentralS01()
    singleDevGithubProject("Colter23", "bilibili-dynamic-mirai-plugin")
    licenseFromGitHubProject("AGPL-3.0", "master")
    publication {
        artifact(tasks.getByName("buildPlugin"))
    }
}

dependencies {
    implementation("io.ktor:ktor-client-okhttp:3.0.3") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-client-encoding:3.0.3") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.slf4j")
    }

    implementation("com.google.zxing:javase:3.5.0")
    compileOnly("xyz.cssxsh.mirai:mirai-skia-plugin:1.3.1")

    testImplementation(kotlin("test", "1.7.0"))
    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.7.27")
    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.7.27")
    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-linux-arm64:0.7.27")
    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-macos-x64:0.7.27")
    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.7.27")
}

mirai {
    jvmTarget = JavaVersion.VERSION_11
}

// 配置 Shadow 插件以重定向 ktor 包，避免与 Overflow/Mirai Console 的 ktor 冲突
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    
    // 重定向 ktor 包名到插件内部的命名空间
    relocate("io.ktor", "top.colter.mirai.plugin.bilibili.shadow.ktor")
    
    // 只打包 ktor 相关依赖
    dependencies {
        include(dependency("io.ktor:.*"))
    }
}

// 让 buildPlugin 任务依赖 shadowJar
afterEvaluate {
    tasks.findByName("buildPlugin")?.dependsOn("shadowJar")
}
