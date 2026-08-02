plugins {
    `java-library`
}

group = "github.freshchromatic"
version = project(":projects:ChunkRevive").version

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.compileJava {
    options.encoding = Charsets.UTF_8.name()
    options.release.set(21)
}
