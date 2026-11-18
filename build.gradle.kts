import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    `maven-publish`
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.24"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"
val leafMavenPublicUrl = "https://maven.leafmc.one/snapshots/"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven(leafMavenPublicUrl)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test>().configureEach {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

}

paperweight {
    // Pyzh - Leaf is a real upstream: its patches live in Leaf's repo, not ours.
    // Updating Leaf is a `leafCommit` bump. applyUpstreamNested defaults to true, so
    // paperweight runs Leaf's own Paper-application before applying our patches.
    upstreams.register("leaf") {
        repo = github("Winds-Studio", "Leaf")
        ref = providers.gradleProperty("leafCommit")

        patchFile {
            path = "leaf-server/build.gradle.kts"
            outputFile = file("pyzh-server/build.gradle.kts")
            patchFile = file("pyzh-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "leaf-api/build.gradle.kts"
            outputFile = file("pyzh-api/build.gradle.kts")
            patchFile = file("pyzh-api/build.gradle.kts.patch")
        }

        // Leaf's already-patched paper-api, plus ours on top.
        // patchRepo, not patchDir: Leaf's generated paper-api is already a git repo (paperweight
        // tracks patches there). FilterRepo copies the input wholesale including its .git and then
        // copies gitDir onto .git, which collides. RepoPatchSet is the variant for git-backed inputs.
        patchRepo("leafApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("pyzh-api/paper-patches")
            outputDir = file("paper-api")
        }

        // Leaf's own hand-written source (org.dreeam.leaf.*, org.leavesmc.leaves.*).
        // Our edits to it live as patches instead of in-place edits.
        patchDir("leafServerSource") {
            upstreamPath = "leaf-server/src/main"
            patchesDir = file("pyzh-server/leaf-patches")
            outputDir = file("pyzh-server/src/main")
        }
        patchDir("leafApiSource") {
            upstreamPath = "leaf-api/src/main"
            patchesDir = file("pyzh-api/leaf-patches")
            outputDir = file("pyzh-api/src/main")
        }
    }
}
