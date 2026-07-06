plugins {
    kotlin("jvm") version "1.9.24"
    application
}

group = "com.rushi.pathfilter"
version = "1.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Gradle 9 no longer puts the launcher on the test classpath automatically.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    applicationName = "pathfilter"
    mainClass.set("com.pathfilter.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// Fat jar with the Kotlin stdlib bundled in, runnable via plain `java -jar`.
val executableJar = tasks.register<Jar>("executableJar") {
    group = "build"
    description = "Assembles a self-contained executable jar (build/libs/pathfilter-all.jar)."
    archiveBaseName.set("pathfilter")
    archiveClassifier.set("all")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

tasks.named("assemble") {
    dependsOn(executableJar)
}
