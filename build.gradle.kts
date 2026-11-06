plugins {
    kotlin("jvm") version "2.1.0"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.yuncan"
version = "1.0.5"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    // WebSocket 服务器
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    // JSON 处理（paper-api 自带 gson，但显式声明更稳定）
    implementation("com.google.code.gson:gson:2.15.0")
    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // ItemsAdder API（soft）
    compileOnly("com.github.LoneDev6:API-ItemsAdder:3.6.1")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    shadowJar {
        archiveBaseName.set("OurcraftTrmenu-Server")
        archiveClassifier.set("")
        // 重定位依赖以避免冲突
        relocate("org.java_websocket", "com.yuncan.ourcrafttrmenu.libs.websocket")
        relocate("org.jetbrains.kotlinx", "com.yuncan.ourcrafttrmenu.libs.kotlinx")
    }
    build {
        dependsOn(shadowJar)
    }
    // 替换 plugin.yml 中的 ${version} 占位符
    processResources {
        expand("version" to project.version)
    }
}
