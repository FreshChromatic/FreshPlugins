plugins {
    `java-library`
}

group = "github.freshchromatic.chunkrevive.nms"
version = project(":projects:ChunkRevive").version

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // The boundary only uses stable Bukkit types; keep it on the oldest supported API baseline.
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.compileJava {
    options.encoding = Charsets.UTF_8.name()
    options.release.set(21)
}
