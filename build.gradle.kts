// Human-owned build file (build policy): versions are pinned and locked so offline builds resolve
// from the prepared Gradle cache. Implementation work must not edit this file; report needed libraries instead.
plugins {
    java
    id("org.springframework.boot") version "4.0.9"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.emberline"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencyLocking { lockAllConfigurations() }

val archTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[archTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[archTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.0.9")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("tools.jackson.dataformat:jackson-dataformat-csv")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.duckdb:duckdb_jdbc:2.0.0.1")
    implementation("org.locationtech.jts:jts-core:1.20.0")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.22.0")
    implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.22.0")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64:18.6.0")
    testImplementation("com.github.codemonstur:embedded-redis:1.4.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

val archTestTask = tasks.register<Test>("archTest") {
    description = "Structure rules (ArchUnit + Spring Modulith verify)."
    group = "verification"
    testClassesDirs = archTest.output.classesDirs
    classpath = archTest.runtimeClasspath
}
tasks.check { dependsOn(archTestTask) }

tasks.bootJar { archiveFileName = "emberline.jar" }
tasks.jar { enabled = false }
