plugins {
    id("com.gradleup.shadow") version "9.4.1" apply false
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
    id("xyz.jpenilla.run-paper") version "3.0.2" apply false
    id("de.eldoria.plugin-yml.paper") version "0.9.0" apply false
}

allprojects {
    group = "github.freshchromatic"
    description = "FreshChromatic Projects"

    repositories {
        mavenLocal()
        mavenCentral()

        maven(url = "https://repo.papermc.io/repository/maven-public/")
    }
}
