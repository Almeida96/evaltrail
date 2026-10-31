plugins {
    application
    jacoco
}

group = "io.github.almeida96"
version = "0.1.0"
repositories { mavenCentral() }
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
application { mainClass = "io.github.almeida96.evaltrail.Main" }
tasks.processResources { from("examples") { include("support-suite.json"); into("examples") } }
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
tasks.test {
    useJUnitPlatform()
    systemProperty("evaltrail.classpath", sourceSets.main.get().runtimeClasspath.asPath)
    finalizedBy(tasks.jacocoTestReport)
}
jacoco { toolVersion = "0.8.14" }
tasks.jacocoTestReport { reports { xml.required = true; html.required = true } }
distributions {
    main {
        contents {
            from("README.md", "LICENSE", "CONTRIBUTING.md", "CHANGELOG.md")
            from("docs") { into("docs") }
            from("examples") { into("examples") }
        }
    }
}
dependencyLocking { lockAllConfigurations() }
