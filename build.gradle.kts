import java.util.*

plugins {
    kotlin("jvm") version "1.6.10"
    java
    signing
    id("org.jetbrains.dokka") version "1.6.0"
    `maven-publish`
}
val kotlinVersion = "1.6.10"

group = "com.lightningkite"

repositories {
    mavenCentral()
    google()
    maven(url = "https://plugins.gradle.org/m2/")
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class).all {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    api(localGroovy())
    api(gradleApi())

    api(group = "org.jetbrains.kotlin", name = "kotlin-gradle-plugin", version = kotlinVersion)
    api(group = "org.jetbrains.kotlin", name = "kotlin-gradle-plugin-api", version = kotlinVersion)

    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
}


fun MavenPom.github(group: String, repo: String) {
    url.set("https://github.com/$group/$repo")
    scm {
        connection.set("scm:git:https://github.com/$group/$repo.git")
        developerConnection.set("scm:git:https://github.com/$group/$repo.git")
        url.set("https://github.com/$group/$repo")
    }
}

fun MavenPomLicenseSpec.mit() {
    license {
        name.set("The MIT License (MIT)")
        url.set("https://www.mit.edu/~amini/LICENSE.md")
        distribution.set("repo")
    }
}

fun MavenPomDeveloperSpec.developer(
    id: String,
    name: String,
    email: String
) {
    developer {
        this.id.set(id)
        this.name.set(name)
        this.email.set(email)
    }
}

var Task.published: Boolean
    get() = this.extensions.extraProperties.has("published") && this.extensions.extraProperties.get("published") as Boolean
    set(value) {
        this.extensions.extraProperties.set("published", value)
        this.project.artifacts.add("archives", this)
    }

fun Project.sources(generateJavadoc: Boolean) {
    tasks.apply {
        this.create("sourceJar", org.gradle.jvm.tasks.Jar::class.java) {
            archiveClassifier.set("sources")
            sourceSets.asMap.values.forEach { s ->
                from(s.allSource.srcDirs)
            }
            from(project.projectDir.resolve("src/include"))
            published = true
        }
        this.create("javadocJar", org.gradle.jvm.tasks.Jar::class.java) {
            dependsOn("dokkaJavadoc")
            archiveClassifier.set("javadoc")
            from(project.file("build/dokka/javadoc"))
            published = generateJavadoc
        }
    }
}

fun File.runCli(vararg args: String): String {
    val process = ProcessBuilder(*args)
        .directory(this)
        .start()
    process.outputStream.close()
    return process.inputStream.readAllBytes().toString(Charsets.UTF_8)
}

fun File.getGitBranch(): String = runCli("git", "rev-parse", "--abbrev-ref", "HEAD").trim()
fun File.getGitHash(): String = runCli("git", "rev-parse", "--short", "HEAD").trim()
fun File.getGitTag(): String? = runCli("git", "tag", "--points-at", getGitHash()).trim().takeUnless { it.isBlank() }

fun Project.standardPublishing(pom: MavenPom.() -> Unit) {
    this.version = project.rootDir.run {
        getGitTag() ?: (getGitBranch() + "-SNAPSHOT")
    }
    val props = project.rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { stream ->
        Properties().apply { load(stream) }
    }

    val signingKey: String? = (System.getenv("SIGNING_KEY")?.takeUnless { it.isEmpty() }
        ?: props?.getProperty("signingKey")?.toString())
        ?.lineSequence()
        ?.filter {
            it.trim().firstOrNull()?.let { it.isLetterOrDigit() || it == '=' || it == '/' || it == '+' } == true
        }
        ?.joinToString("\n")
    val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeUnless { it.isEmpty() }
        ?: props?.getProperty("signingPassword")?.toString()
    val useSigning = signingKey != null && signingPassword != null

    if (signingKey != null) {
        if (!signingKey.contains('\n')) {
            throw IllegalArgumentException("Expected signing key to have multiple lines")
        }
        if (signingKey.contains('"')) {
            throw IllegalArgumentException("Signing key has quote outta nowhere")
        }
    }

    val deploymentUser = (System.getenv("OSSRH_USERNAME")?.takeUnless { it.isEmpty() }
        ?: props?.getProperty("ossrhUsername")?.toString())
        ?.trim()
    val deploymentPassword = (System.getenv("OSSRH_PASSWORD")?.takeUnless { it.isEmpty() }
        ?: props?.getProperty("ossrhPassword")?.toString())
        ?.trim()
    val useDeployment = deploymentUser != null && deploymentPassword != null

    sources(generateJavadoc = props?.getProperty("generateJavadoc")?.toBoolean() ?: true)

    publishing {
        publications {
            create("main", MavenPublication::class.java) {
                val component = components.findByName("release") ?: components.findByName("kotlin")
                from(component)
                for (task in tasks.asMap.values) {
                    if (task.published)
                        artifact(task)
                }
                pom { pom(this) }
            }
        }
        if (useDeployment) {
            repositories.apply {
                maven {
                    name = "sonatype"
                    if (version.toString().endsWith("-SNAPSHOT")) {
                        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                    } else {
                        url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                    }
                    credentials {
                        username = deploymentUser
                        password = deploymentPassword
                    }
                }
            }
        }
    }
    if (useSigning) {
        signing {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications)
        }
    }
}

standardPublishing {
    name.set("Deploy Helpers")
    description.set("Deployment stuff goes here because we're getting tired of these scripts")
    github("lightningkite", "gradle-deploy-helpers")
    licenses {
        mit()
    }
    developers {
        developer(
            id = "LightningKiteJoseph",
            name = "Joseph Ivie",
            email = "joseph@lightningkite.com"
        )
    }
}
