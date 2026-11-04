import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.20"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "sea.balance.craftdrawers"
version = "1.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://jitpack.io")
}

val craftEngineVersion = "26.9.2"
val craftEngineSha512 = "55bb3737df295490e0a446ee3daac6b72052e0e47aa7d8cda14455c03b6df08f6fea3be66e02d04007a3042ec153c8b7e195e66a38f75f64f08698340542d04c"
val craftEngineApiJar = layout.buildDirectory.file("compile-only/craft-engine-paper-plugin-$craftEngineVersion.jar").get().asFile
val downloadCraftEngineApi = tasks.register("downloadCraftEngineApi") {
    inputs.property("version", craftEngineVersion)
    inputs.property("sha512", craftEngineSha512)
    outputs.file(craftEngineApiJar)
    doLast {
        val bytes = if (craftEngineApiJar.isFile) {
            craftEngineApiJar.readBytes()
        } else {
            URI(
                "https://cdn.modrinth.com/data/tRX6FMfQ/versions/mwFiNxVO/craft-engine-paper-plugin-26.9.2.jar",
            ).toURL().openStream().use { it.readBytes() }
        }
        val digest = MessageDigest.getInstance("SHA-512").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(digest == craftEngineSha512) { "CraftEngine $craftEngineVersion SHA-512 mismatch: $digest" }
        if (!craftEngineApiJar.isFile) {
            craftEngineApiJar.parentFile.mkdirs()
            craftEngineApiJar.writeBytes(bytes)
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    compileOnly(files(craftEngineApiJar).builtBy(downloadCraftEngineApi))
    compileOnly("com.github.Zrips:Residence:6.0.2.3") { isTransitive = false }
    compileOnly("com.github.Zrips:CMILib:1.5.9.6") { isTransitive = false }
    compileOnly("cn.lunadeer:DominionAPI:4.9.6") { isTransitive = false }
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveFileName.set("CraftDrawers-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register<ShadowJar>("stressTestJar") {
    dependsOn(tasks.classes, downloadCraftEngineApi)
    archiveFileName.set("CraftDrawers-Test-${project.version}.jar")
    configurations = listOf(project.configurations.runtimeClasspath.get())
    from(sourceSets.main.get().output)
    from(layout.projectDirectory.dir("src/stressTest/resources"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    exclude("META-INF/versions/**/module-info.class")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named("downloadCraftEngineApi") {
    outputs.upToDateWhen { false }
}

tasks.register<Zip>("craftEnginePackZip") {
    archiveFileName.set("CraftDrawers-CraftEngine-Pack-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.dir("craftengine-pack"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.named("shadowJar"))
    dependsOn(tasks.named("craftEnginePackZip"))
}

tasks.processResources {
    inputs.property("pluginVersion", project.version)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}
