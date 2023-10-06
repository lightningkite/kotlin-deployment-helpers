package com.lightningkite.deployhelpers

import com.android.build.gradle.LibraryExtension
import groovy.lang.GroovyObject
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPomDeveloperSpec
import org.gradle.api.publish.maven.MavenPomLicenseSpec
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.util.*

private class MarkerClass

fun MavenPom.github(group: String, repo: String) {
    url.set("https://github.com/$group/$repo")
    scm {
        it.connection.set("scm:git:https://github.com/$group/$repo.git")
        it.developerConnection.set("scm:git:https://github.com/$group/$repo.git")
        it.url.set("https://github.com/$group/$repo")
    }
}

fun MavenPomLicenseSpec.mit() {
    license {
        it.name.set("The MIT License (MIT)")
        it.url.set("https://www.mit.edu/~amini/LICENSE.md")
        it.distribution.set("repo")
    }
}

fun MavenPomDeveloperSpec.developer(
    id: String,
    name: String,
    email: String
) {
    developer {
        it.id.set(id)
        it.name.set(name)
        it.email.set(email)
    }
}

var Task.published: Boolean
    get() = this.extensions.extraProperties.has("published") && this.extensions.extraProperties.get("published") as Boolean
    set(value) {
        this.extensions.extraProperties.set("published", value)
        this.project.artifacts.add("archives", this)
    }

val Any?.groovyObject: GroovyObject? get() = this as? GroovyObject
fun GroovyObject.getPropertyAsObject(key: String): GroovyObject? = getProperty(key) as? GroovyObject

fun Project.sources(publishJavadoc: Boolean) {
    tasks.apply {
        this.create("sourceJar", Jar::class.java) {
            it.archiveClassifier.set("sources")
            sourceSets.asMap.values.forEach { s ->
                it.from(s.allSource.srcDirs)
            }
            extensions.findByName("android")
                ?.let { it as? LibraryExtension }
                ?.sourceSets
                ?.forEach { set ->
                    set.java.srcDirs.forEach { f ->
                        it.from(f)
                    }
                }
            it.from(project.projectDir.resolve("src/include"))
            it.published = true
        }
        this.create("javadocJar", Jar::class.java) {
            it.dependsOn("dokkaJavadoc")
            it.archiveClassifier.set("javadoc")
            it.from(project.file("build/dokka/javadoc"))
            it.published = publishJavadoc
        }
    }
}


internal fun File.runCli(vararg args: String): String {
    val process = ProcessBuilder(*args)
        .directory(this)
        .start()
    process.outputStream.close()
    return process.inputStream.readAllBytes().toString(Charsets.UTF_8)
}

internal fun File.getGitBranch(): String = runCli("git", "rev-parse", "--abbrev-ref", "HEAD").trim()
internal fun File.getGitHash(): String = runCli("git", "rev-parse", "--short", "HEAD").trim()
internal fun File.isGitClean(): Boolean = runCli("git", "status").contains("working tree clean", true)
internal fun File.getGitTag(): String? =
    runCli("git", "tag", "--points-at", getGitHash()).trim().takeUnless { it.isBlank() }

fun Project.standardPublishing(pom: MavenPom.() -> Unit) {
    val branch = project.rootDir.run { getGitBranch() }
    val branchVersion = "$branch-SNAPSHOT"
    this.version = System.getenv("GITHUB_REF")?.takeIf { it.startsWith("refs/tags/") }?.let {
        it.removePrefix("refs/tags/")
    }
        ?: System.getenv("PUBLISH_VERSION")?.takeUnless { it.isEmpty() }
        ?: branchVersion
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

    if(plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
        // TODO: add docs, but not sources.  Sources are automatic
    } else {
        sources(publishJavadoc = props?.getProperty("publishJavadoc")?.toBoolean() ?: true)
    }

    publishing {
        it.publications {
            afterEvaluate { _ ->
                if (it.size > 0) {
                    it.toList().filterIsInstance<MavenPublication>().forEach { p ->
                        for (task in tasks.asMap.values) {
                            if (task.published) {
                                println("Adding task ${task.name} to ${p.name}")
                                p.artifact(task)
                            }
                        }
                        p.pom { pom(it) }
                    }
                } else {
                    it.create("main", MavenPublication::class.java) {
                        val component = components.findByName("release") ?: components.findByName("kotlin")
                        it.from(component)
                        for (task in tasks.asMap.values) {
                            if (task.published)
                                it.artifact(task)
                        }
                        it.pom { pom(it) }
                    }
                }
            }
        }
        if (useDeployment) {
            it.repositories.apply {
                maven {
                    it.name = "sonatype"
                    if (version.toString().endsWith("-SNAPSHOT")) {
                        it.url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                    } else {
                        it.url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                    }
                    it.credentials {
                        it.username = deploymentUser
                        it.password = deploymentPassword
                    }
                }
            }
        }
    }
    if (useSigning) {
        signing {
            it.useInMemoryPgpKeys(signingKey, signingPassword)
            it.sign(publishing.publications)
        }
    }
    tasks.create("setupGitHubActions").apply {
        group = "help"
        doLast {
            setupGitHubAction("both/testPR.yml")
            setupGitHubAction("library/release.yml")
            setupGitHubAction("library/snapshot.yml")
        }
    }
}

private val loader = MarkerClass::class.java.classLoader
private fun Project.setupGitHubAction(path: String) {
    loader.getResource("githubActions/$path")!!.openStream()
        .copyTo(rootDir.resolve(".github/workflows/${path.substringAfterLast('/')}").apply { parentFile.mkdirs() }
            .outputStream())
}

fun Project.standardApplication() {
    (tasks.getByName("distZip") as? Zip)?.apply {
        archiveFileName.set("distribution.zip")
    }
    tasks.create("setupGitHubActions").apply {
        group = "help"
        doLast {
            setupGitHubAction("both/testPR.yml")
            setupGitHubAction("app/release.yml")
            setupGitHubAction("app/testMaster.yml")
        }
    }
}