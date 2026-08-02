import net.minecrell.pluginyml.paper.PaperPluginDescription

plugins {
    id("java-library")
    id("maven-publish")
    id("com.gradleup.shadow")
    id("de.eldoria.plugin-yml.paper")
    id("io.papermc.paperweight.userdev")
}

group = "org.freshchromatic"
version = findProperty("freshlibVersion") as String
description = "Library for all FreshChromatic projects"

repositories {
    maven("https://repo.nexomc.com/releases") // Nexo
    maven("https://maven.devs.beer/") // ItemsAdder
    maven("https://repo.codemc.io/repository/maven-releases/") // HeadDatabase-API
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // The Paper 1.21.11 dev bundle omits this dependency's version. Keep it
    // aligned with the Adventure version required by paper-api.
    compileOnly("net.kyori:adventure-text-serializer-ansi:4.26.1")
    compileOnly("io.netty:netty-all:4.1.107.Final")

    // Cloud command framework
    api("org.incendo:cloud-paper:2.0.0-beta.17")
    api("org.incendo:cloud-minecraft-extras:2.0.0-beta.17")
    api("org.incendo:cloud-processors-confirmation:1.0.0-rc.1")

    // Dependency injection
    api("com.google.inject:guice:7.0.0")

    // Configuration
    api("org.spongepowered:configurate-yaml:4.1.2")

    // database drivers
    compileOnly("org.xerial:sqlite-jdbc:3.51.2.0")
    compileOnly("com.mysql:mysql-connector-j:9.6.0")

    // Optional material-tag resolver integrations (soft-depend, see FreshLibPlugin)
    compileOnly("com.nexomc:nexo:1.0.0")
    compileOnly("dev.lone:api-itemsadder:4.0.10")
    compileOnly("com.arcaniax:HeadDatabase-API:1.3.2")

    // testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:6.0.3")
    testImplementation("org.junit.platform:junit-platform-console-standalone:6.0.3")
    testImplementation("com.google.code.gson:gson:2.13.2")
    // Required only while Gradle runs tests; Paper supplies it on a server.
    testRuntimeOnly("net.kyori:adventure-text-serializer-ansi:4.26.1")
}

paper {
    name = "FreshLib"
    main = "github.freshchromatic.freshlib.FreshLibPlugin"
    foliaSupported = true
    version = findProperty("freshlibVersion") as String
    description = "Library for all FreshChromatic projects"
    apiVersion = "1.21"
    serverDependencies {
        // joinClasspath is required even though these are compileOnly at build time — compileOnly
        // only affects compilation, the actual ItemsAdder/Nexo/HeadDatabase classes at runtime
        // only exist inside their own plugin's classloader. required=false: these are optional,
        // Paper just won't grant classpath access if the plugin isn't installed.
        register("ItemsAdder") {
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            required = false
            joinClasspath = true
        }
        register("Nexo") {
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            required = false
            joinClasspath = true
        }
        register("HeadDatabase") {
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            required = false
            joinClasspath = true
        }
    }
}

tasks {

    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveClassifier.set("")
        // Not relocated: cloud-paper/guice/configurate-yaml are part of FreshLib's public
        // surface (declared as `api`) — consuming plugins reference these packages directly
        // (e.g. AssistiveFeatures' AFCommands uses org.incendo.cloud.CommandManager without
        // its own cloud-paper dependency). Relocating them breaks every such consumer at runtime.
    }

    reobfJar {
        enabled = false
    }

    compileJava {
        options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything

        // Set the release flag. This configures what version bytecode the compiler will emit, as well as what JDK APIs are usable.
        // See https://openjdk.java.net/jeps/247 for more information.
        options.release.set(21)
    }

    java {
        withSourcesJar()
        withJavadocJar()
    }

    javadoc {
        options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
        val options = options as StandardJavadocDocletOptions
        options.addStringOption("Xdoclint:none", "-quiet")
    }
    processResources {
        filteringCharset = Charsets.UTF_8.name() // We want UTF-8 for everything
    }

    test {
        useJUnitPlatform()
    }
}
