plugins {
    application
    kotlin("jvm") version "2.2.20"
}

group = "automation"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("commons-codec:commons-codec:1.15")
    implementation("com.microsoft.playwright:playwright:1.55.0")
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
    implementation("org.apache.commons:commons-csv:1.10.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(22)
}