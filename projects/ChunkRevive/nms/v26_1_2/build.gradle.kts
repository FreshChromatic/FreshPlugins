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
    paperweight.paperDevBundle("26.1.2.build.74-stable")
    implementation(project(":projects:ChunkRevive:nms:api"))
    compileOnly(project(":libraries:FreshLib"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

tasks {
    test { useJUnitPlatform() }
    reobfJar { enabled = false }
    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(25)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
