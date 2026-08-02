import org.gradle.jvm.tasks.Jar

plugins {
    java
    id("com.gradleup.shadow")
}

group = "github.freshchromatic"
version = "1.0.0"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/")
    maven(url = "https://jitpack.io/")
    maven(url = "https://repo.codemc.io/repository/maven-releases/")
    maven(url = "https://maven.pvphub.me/tofaa")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    implementation(project(":projects:ChunkRevive:api"))

    implementation(project(":projects:ChunkRevive:nms:api"))
    runtimeOnly(project(":projects:ChunkRevive:nms:v1_21_11"))
    runtimeOnly(project(":projects:ChunkRevive:nms:v26_1_2"))
    runtimeOnly(project(":projects:ChunkRevive:nms:v26_2"))

    compileOnly(project(":libraries:FreshLib"))

    // EntityLib — direct WrapperEntity usage for TextDisplay markers
    implementation("io.github.tofaa2:spigot:3.0.3-SNAPSHOT")

    // PacketEvents (EntityLib runtime dep; provided by server)
    compileOnly("com.github.retrooper:packetevents-spigot:2.11.0")

    // Cloud command framework now comes from FreshLib's shared joinClasspath copy
    // (bumped to beta.15 there) — declaring our own copy here causes a
    // loader-constraint LinkageError when crossing into FreshLib's cloud-minecraft-extras.

    // Residence soft-dependency
    compileOnly(files("libs/Residence6.0.1.6.jar"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(project(":libraries:FreshLib"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.51.2.0")
}

tasks {
    val verifyNmsBoundary by registering {
        val mainSources = fileTree("src/main/java") { include("**/*.java") }
        inputs.files(mainSources)
        doLast {
            val forbidden = Regex("^\\s*import\\s+(net\\.minecraft|org\\.bukkit\\.craftbukkit|ca\\.spottedleaf)\\.")
            val violations = mainSources.files.flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) "${source.relativeTo(projectDir)}:${index + 1}" else null
                }
            }
            if (violations.isNotEmpty()) {
                throw GradleException("NMS imports leaked into the main module:\n${violations.joinToString("\n")}")
            }
        }
    }

    val verifyFeatureBoundary by registering {
        val featureSources = fileTree("src/main/java/github/freshchromatic/chunkrevive/feature") {
            include("**/*.java")
        }
        inputs.files(featureSources)
        doLast {
            val forbidden = listOf(
                Regex("^\\s*import\\s+org\\.incendo\\.cloud\\."),
                Regex("^\\s*import\\s+github\\.freshchromatic\\.chunkrevive\\.presentation\\."),
                Regex("^\\s*import\\s+github\\.freshchromatic\\.chunkrevive\\.infrastructure\\.")
            )
            val violations = featureSources.files.flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.any { it.containsMatchIn(line) }) {
                        "${source.relativeTo(projectDir)}:${index + 1}"
                    } else null
                }
            }
            if (violations.isNotEmpty()) {
                throw GradleException(
                    "Presentation or infrastructure leaked into the feature layer:\n" +
                        violations.joinToString("\n")
                )
            }
        }
    }

    val verifyIntegrationBoundary by registering {
        val mainSources = fileTree("src/main/java") { include("**/*.java") }
        inputs.files(mainSources)
        doLast {
            val residenceRoot = file(
                "src/main/java/github/freshchromatic/chunkrevive/integration/residence"
            ).toPath()
            val violations = mainSources.files.flatMap { source ->
                if (source.toPath().startsWith(residenceRoot)) return@flatMap emptyList()
                source.readLines().mapIndexedNotNull { index, line ->
                    if (Regex("^\\s*import\\s+com\\.bekvon\\.bukkit\\.residence\\.")
                            .containsMatchIn(line)) {
                        "${source.relativeTo(projectDir)}:${index + 1}"
                    } else null
                }
            }
            if (violations.isNotEmpty()) {
                throw GradleException(
                    "Residence API leaked outside its integration adapter:\n" +
                        violations.joinToString("\n")
                )
            }
        }
    }

    check {
        dependsOn(verifyNmsBoundary)
        dependsOn(verifyFeatureBoundary)
        dependsOn(verifyIntegrationBoundary)
        dependsOn(":projects:ChunkRevive:nms:v26_1_2:check")
        dependsOn(":projects:ChunkRevive:nms:v26_2:check")
    }

    test {
        useJUnitPlatform()
    }

    shadowJar {
        val nmsAdapters = listOf(
            project(":projects:ChunkRevive:nms:v1_21_11"),
            project(":projects:ChunkRevive:nms:v26_1_2"),
            project(":projects:ChunkRevive:nms:v26_2")
        )
        nmsAdapters.forEach { adapter ->
            val adapterJar = adapter.tasks.named<Jar>("jar")
            dependsOn(adapterJar)
            from(adapterJar.map { zipTree(it.archiveFile) })
        }
        mergeServiceFiles()
        val libBase = "github.freshchromatic.chunkrevive.libs"
        relocate("io.github.tofaa2.entitylib", "$libBase.entitylib")
        archiveClassifier.set("")
    }

    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release = 21
        options.compilerArgs.add("-parameters")
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name()
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
