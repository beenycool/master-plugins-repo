import org.gradle.language.jvm.tasks.ProcessResources

val paperVersion = "26.3.build.240-stable"

allprojects {
    group = "gg.guab"
    version = "0.2.0"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    dependencies {
        add("compileOnly", "io.papermc.paper:paper-api:$paperVersion")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    tasks.named<ProcessResources>("processResources") {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
}
