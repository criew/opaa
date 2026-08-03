plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.spotless)
}

group = "io.opaa"
version = "0.0.1-SNAPSHOT"
description = "OPAA Spring Boot Backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
    }
    // Retrieval-quality evaluation harness (issue #227). Deliberately its own source set, not
    // part of `test`: it needs Docker (pgvector + Ollama Testcontainers), pulls a ~275 MB
    // embedding model and indexes ~1.450 corpus documents, which would slow down every
    // `./gradlew build` for something that only needs to run occasionally and by choice. See the
    // `evaluateRetrieval` task below and docs/discussions/discussion-rag-evaluation.md.
    create("evalTest") {
        java.srcDir("src/evalTest/java")
        resources.srcDir("src/evalTest/resources")
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += output + sourceSets.main.get().output + sourceSets.test.get().output
    }
}

configurations.named("evalTestImplementation") {
    extendsFrom(configurations["testImplementation"])
}
configurations.named("evalTestRuntimeOnly") {
    extendsFrom(configurations["testRuntimeOnly"])
}

dependencies {
    implementation(libs.bundles.spring.boot)
    implementation(libs.bundles.spring.ai)
    implementation(libs.spring.boot.starter.liquibase)
    implementation(libs.caffeine)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.bundles.jjwt.runtime)
    runtimeOnly(libs.bundles.runtime)
    testImplementation(libs.bundles.test.deps)
    testRuntimeOnly(libs.bundles.test.runtime.deps)
}

// Runs the retrieval-quality evaluation harness against eval/corpus (issue #227). Not wired into
// `check`/`build`/`test` on purpose (see the `evalTest` source set comment above) — invoke
// explicitly with `./gradlew evaluateRetrieval`. Needs Docker; downloads the `nomic-embed-text`
// model into the Ollama Testcontainer on first run.
tasks.register<Test>("evaluateRetrieval") {
    description = "Runs the retrieval-quality evaluation harness (Hit Rate, MRR, nDCG, Recall) " +
        "against eval/corpus using Testcontainers (pgvector + Ollama). Not part of build/check."
    group = "verification"
    testClassesDirs = sourceSets["evalTest"].output.classesDirs
    classpath = sourceSets["evalTest"].runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("passed", "skipped", "failed", "standard_out")
        showStandardStreams = true
    }
}

// Fast, Docker-free unit tests for the pure metric math (RetrievalMetrics, MetricsAggregate,
// CorpusManifest — see their Javadoc). Lives in the evalTest source set (not `main`/`test`) so the
// classes under test never ship in the production jar, but still runs as part of `check` so a
// Spring AI/Testcontainers upgrade that breaks compilation, or a metric-math regression, is caught
// without Docker. Explicitly excludes RetrievalEvaluationHarnessTest, which needs Testcontainers
// and stays exclusive to `evaluateRetrieval` — issue #227's exclusion criterion is about that one
// Docker-requiring test class, not about the evalTest source set as a whole.
tasks.register<Test>("evalUnitTest") {
    description = "Docker-free unit tests for the eval metric math (RetrievalMetrics, " +
        "MetricsAggregate, CorpusManifest). Part of check; does not touch Testcontainers."
    group = "verification"
    testClassesDirs = sourceSets["evalTest"].output.classesDirs
    classpath = sourceSets["evalTest"].runtimeClasspath
    useJUnitPlatform()
    filter {
        excludeTestsMatching("*RetrievalEvaluationHarnessTest")
    }
}

tasks.named("check") {
    dependsOn("evalUnitTest")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
    }
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

// Deutsche Texte in Quellen und Ressourcen sind UTF-8. Ohne diese Einstellung
// nutzt javac das Plattform-Encoding, was auf Windows Umlaute verfälscht.
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    systemProperty("file.encoding", "UTF-8")
}

tasks.named<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("openApiGenerate") {
    generatorName.set("spring")
    inputSpec.set(layout.projectDirectory.file("src/main/resources/openapi/opaa-api.yaml").asFile.absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    modelPackage.set("io.opaa.api.dto")

    globalProperties.set(
        mapOf(
            "models" to "",
            "apis" to "false",
            "supportingFiles" to "false",
            "modelDocs" to "false",
            "modelTests" to "false",
            "apiDocs" to "false",
            "apiTests" to "false",
        )
    )

    configOptions.set(
        mapOf(
            "useSpringBoot3" to "true",
            "useJakartaEe" to "true",
            "useBeanValidation" to "true",
            "openApiNullable" to "false",
            "documentationProvider" to "none",
            "annotationLibrary" to "none",
            "dateLibrary" to "custom",
        )
    )

    typeMappings.set(mapOf(
        "DateTime" to "Instant",
        "SpaceRole" to "SpaceRole",
        "SpaceKind" to "SpaceKind",
        "SpaceVisibility" to "SpaceVisibility",
        "SystemRole" to "SystemRole",
        "GroupKind" to "GroupKind",
        "DirectorySyncOutcome" to "DirectorySyncOutcome",
    ))
    importMappings.set(mapOf(
        "Instant" to "java.time.Instant",
        "SpaceRole" to "io.opaa.space.SpaceRole",
        "SpaceKind" to "io.opaa.space.SpaceKind",
        "SpaceVisibility" to "io.opaa.space.SpaceVisibility",
        "SystemRole" to "io.opaa.auth.SystemRole",
        "GroupKind" to "io.opaa.group.GroupKind",
        "DirectorySyncOutcome" to "io.opaa.group.sync.DirectorySyncOutcome",
    ))
}

tasks.named<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("openApiGenerate") {
    doLast {
        // Remove generated enum files that are mapped to existing domain enums via typeMappings.
        // The generator still creates these files even with typeMappings configured.
        val generatedDir = layout.buildDirectory.dir("generated/openapi/src/main/java/io/opaa/api/dto").get().asFile
        listOf("SpaceRole.java", "SpaceKind.java", "SpaceVisibility.java", "SystemRole.java", "GroupKind.java", "DirectorySyncOutcome.java").forEach { fileName ->
            file("$generatedDir/$fileName").delete()
        }
    }
}

tasks.named("compileJava") {
    dependsOn("openApiGenerate")
}
