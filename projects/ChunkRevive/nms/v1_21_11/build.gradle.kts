plugins {
    `java-library`
    id("io.papermc.paperweight.userdev")
}

group = "github.freshchromatic.chunkrevive.nms"
version = project(":projects:ChunkRevive").version

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    // Paper's 1.21.11 dev-bundle metadata omits this transitive dependency's version.
    compileOnly("net.kyori:adventure-text-serializer-ansi:4.25.0")
    implementation(project(":projects:ChunkRevive:nms:api"))
    compileOnly(project(":libraries:FreshLib"))
    testImplementation("net.kyori:adventure-text-serializer-ansi:4.25.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

tasks {
    test { useJUnitPlatform() }
    reobfJar { enabled = false }
    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(21)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
