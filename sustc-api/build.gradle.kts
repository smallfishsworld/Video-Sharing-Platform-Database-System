import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependencyManagement)
    alias(libs.plugins.lombok)
    alias(libs.plugins.shadow)
    alias(libs.plugins.spotless)
}

// Spring Boot 2.7 manages pgjdbc 42.3.x; 42.7.x has the CVE-2024-1597 fix and a faster COPY path.
extra["postgresql.version"] = libs.versions.postgresql.get()

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // CopyManager is used for bulk import, so the driver is a compile-time dependency.
    implementation("org.postgresql:postgresql")

    // ORM libraries are prohibited in this project; all SQL is hand-written.

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Build with JDK 17 or 21, always emitting Java 17 bytecode.
    options.release = 17
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    // Integration tests apply the real schema scripts from the repository's sql/ directory, so
    // those scripts are inputs: a schema change must re-run the tests, not reuse a cached result.
    inputs.dir(rootProject.file("sql")).withPropertyName("sqlScripts").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("sustc.sql.dir", rootProject.file("sql").absolutePath)
    maxHeapSize = "768m"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

spotless {
    java {
        // Only the code written for this project; the course-provided DTOs and service
        // interfaces keep their original formatting.
        target("src/main/java/io/sustc/service/impl/**/*.java", "src/test/java/**/*.java")
        palantirJavaFormat(libs.versions.palantir.java.format.get())
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<BootRun> {
    enabled = false
}

tasks.withType<BootJar> {
    enabled = false
}

tasks.register("submitJar") {
    group = "application"
    description = "Prepare an uber-JAR for submission"

    tasks.getByName<ShadowJar>("shadowJar") {
        archiveFileName = "sustc-api.jar"
        destinationDirectory = File("$rootDir/submit")
        dependencies {
            exclude(dependency("ch.qos.logback:logback-.*"))
        }
    }.let { dependsOn(it) }
}

tasks.clean {
    delete(fileTree("$rootDir/submit").matching { include("*.jar") })
}
