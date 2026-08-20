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
    implementation(libs.jtokkit)
    implementation(libs.jsoup)
    implementation(libs.tika.core)
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
//
// Produces the report, and only the report: BaselineRegressionTest is excluded because it consumes
// a report that this very task writes (issue #414). Without the filter, JUnit runs it alphabetically
// before RetrievalEvaluationHarnessTest — that is, before build/eval-reports/retrieval-metrics.json
// exists — so the task fails on its "No report found" guard before checkRetrievalBaseline ever gets
// a turn. The three tasks split the evalTest source set along these roles: evalUnitTest = pure
// metric math (Docker-free, part of `check`), evaluateRetrieval = produce the report (needs
// Docker), checkRetrievalBaseline = compare the report against the baseline (Docker-free, depends
// on evaluateRetrieval).
tasks.register<Test>("evaluateRetrieval") {
    description = "Runs the retrieval-quality evaluation harness (Hit Rate, MRR, nDCG, Recall) " +
        "against eval/corpus using Testcontainers (pgvector + Ollama). Not part of build/check."
    group = "verification"
    testClassesDirs = sourceSets["evalTest"].output.classesDirs
    classpath = sourceSets["evalTest"].runtimeClasspath
    useJUnitPlatform()
    filter {
        excludeTestsMatching("*BaselineRegressionTest")
    }
    outputs.upToDateWhen { false }
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("passed", "skipped", "failed", "standard_out")
        showStandardStreams = true
    }
}

// Fast, Docker-free unit tests for the pure metric math (RetrievalMetrics, MetricsAggregate,
// CorpusManifest, BaselineComparator — see their Javadoc). Lives in the evalTest source set (not
// `main`/`test`) so the classes under test never ship in the production jar, but still runs as
// part of `check` so a Spring AI/Testcontainers upgrade that breaks compilation, or a metric-math
// regression, is caught without Docker. Explicitly excludes RetrievalEvaluationHarnessTest (needs
// Testcontainers, stays exclusive to `evaluateRetrieval`) and BaselineRegressionTest (needs a
// report file that only exists after a real `evaluateRetrieval` run, stays exclusive to
// `checkRetrievalBaseline` below) — issue #227/#228's exclusion criterion is about those two
// specific test classes, not about the evalTest source set as a whole.
tasks.register<Test>("evalUnitTest") {
    description = "Docker-free unit tests for the eval metric math (RetrievalMetrics, " +
        "MetricsAggregate, CorpusManifest, BaselineComparator). Part of check; no Testcontainers, " +
        "no report file dependency."
    group = "verification"
    testClassesDirs = sourceSets["evalTest"].output.classesDirs
    classpath = sourceSets["evalTest"].runtimeClasspath
    useJUnitPlatform()
    filter {
        excludeTestsMatching("*RetrievalEvaluationHarnessTest")
        excludeTestsMatching("*BaselineRegressionTest")
    }
}

tasks.named("check") {
    dependsOn("evalUnitTest")
}

// The OpenAI end-to-end tests (io.opaa.integration.*) need a real API key and a network
// round-trip per run. Keeping them inside `test` meant every `./gradlew build` — locally with
// OPAA_OPENAI_API_KEY set, and in the CI `backend-integration` job — recompiled and re-ran the
// whole suite around them (issue #644). They get their own task instead: `test` (and thus
// `build`) never touches them, and CI's backend-integration job invokes only this task.
tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("io.opaa.integration.*")
    }
}

tasks.register<Test>("openAiIntegrationTest") {
    description = "End-to-end tests against the real OpenAI API (io.opaa.integration.*). " +
        "Needs OPAA_OPENAI_API_KEY and Docker; not part of build/check."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.opaa.integration.*")
    }
}

// Compares the report produced by evaluateRetrieval against the committed baseline
// (eval/baseline/comic-characters.json, issue #228). Depends on evaluateRetrieval so a single
// `./gradlew checkRetrievalBaseline` invocation (as used by the nightly/manual/label-triggered CI
// job in .github/workflows/retrieval-regression.yml) runs the full Docker-requiring harness and
// then the baseline comparison, in order. Not part of `check`/`build`/`evalUnitTest` — same
// rationale as `evaluateRetrieval` itself.
tasks.register<Test>("checkRetrievalBaseline") {
    description = "Runs evaluateRetrieval, then fails if the result regresses beyond tolerance " +
        "against eval/baseline/comic-characters.json (issue #228). Needs Docker."
    group = "verification"
    dependsOn("evaluateRetrieval")
    testClassesDirs = sourceSets["evalTest"].output.classesDirs
    classpath = sourceSets["evalTest"].runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    filter {
        includeTestsMatching("*BaselineRegressionTest")
    }
    testLogging {
        events("passed", "skipped", "failed", "standard_out")
        showStandardStreams = true
    }
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
//
// -parameters (#393 code review, nit 4): keeps real parameter names available via reflection
// (Parameter#getName()) instead of javac's default arg0/arg1/... - AuditQueryServiceIntegrationTest
// and AuditControllerTest both reflect over parameter names to prove no access path accepts an
// actor/person filter, and that proof is silently meaningless without this flag.
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    systemProperty("file.encoding", "UTF-8")
}

// #514/PR #537 review (coordinator-reported CI failure): the "test" source set alone now holds
// well over a dozen @SpringBootTest classes that each spin up their own Testcontainers Postgres
// instance and ApplicationContext (io.opaa.api.LibraryControllerCredentialsIntegrationTest and
// its siblings) - Spring's context cache cannot share these across classes once each declares its
// own @DynamicPropertySource method (the cache key resolves the dynamic-property customizer per
// declaring method, not per equivalent body), so several of these heavy contexts are alive at
// once during a single-JVM test run. That, not a leak, is what pushed the CI runner's constrained
// heap into OutOfMemoryError once this PR added one more such context - main itself ran (and
// still runs) green with the pre-existing set. The structural fix for this specific PR was
// removing the added context again (see LibraryControllerCredentialsIntegrationTest's Javadoc);
// this explicit ceiling is additional headroom for the existing, already-tight baseline once
// that's happened, rather than reversible by that fix alone - default JVM heap sizing on
// constrained CI runners is well below what over a dozen concurrently-cached
// EntityManagerFactory/HikariPool/VectorStore instances need. Backend-only: eval tasks stay on
// the default (they run a different, much smaller source set and were never part of this
// problem).
tasks.named<Test>("test") {
    maxHeapSize = "2g"
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
        "LibraryOwnerType" to "LibraryOwnerType",
        "LibraryVisibility" to "LibraryVisibility",
        "DocumentStatus" to "DocumentStatus",
        "DocumentSourceType" to "DocumentSourceType",
        "AssetRole" to "AssetRole",
        "PermissionSubjectType" to "PermissionSubjectType",
        "AuditActorKind" to "ActorKind",
        "AuditSubjectKind" to "AuditSubjectKind",
        "AuditOutcome" to "AuditOutcome",
        "AuditObjectType" to "AuditObjectType",
        "AuditEventType" to "AuditEventType",
        "AuditIncidentScopePurpose" to "AuditIncidentScopePurpose",
        "AuditIncidentScopeStatus" to "AuditIncidentScopeStatus",
        "ChatStatus" to "ChatStatus",
        "ChatRole" to "ChatRole",
        "ColorScheme" to "ColorScheme",
    ))
    importMappings.set(mapOf(
        "Instant" to "java.time.Instant",
        "SpaceRole" to "io.opaa.space.SpaceRole",
        "SpaceKind" to "io.opaa.space.SpaceKind",
        "SpaceVisibility" to "io.opaa.space.SpaceVisibility",
        "SystemRole" to "io.opaa.auth.SystemRole",
        "GroupKind" to "io.opaa.group.GroupKind",
        "DirectorySyncOutcome" to "io.opaa.group.sync.DirectorySyncOutcome",
        "LibraryOwnerType" to "io.opaa.library.LibraryOwnerType",
        "LibraryVisibility" to "io.opaa.library.LibraryVisibility",
        "DocumentStatus" to "io.opaa.indexing.DocumentStatus",
        "DocumentSourceType" to "io.opaa.indexing.DocumentSourceType",
        "AssetRole" to "io.opaa.library.AssetRole",
        "PermissionSubjectType" to "io.opaa.group.PermissionSubjectType",
        "ActorKind" to "io.opaa.audit.ActorKind",
        "AuditSubjectKind" to "io.opaa.audit.AuditSubjectKind",
        "AuditOutcome" to "io.opaa.audit.AuditOutcome",
        "AuditObjectType" to "io.opaa.audit.AuditObjectType",
        "AuditEventType" to "io.opaa.audit.AuditEventType",
        "AuditIncidentScopePurpose" to "io.opaa.audit.AuditIncidentScopePurpose",
        "AuditIncidentScopeStatus" to "io.opaa.audit.AuditIncidentScopeStatus",
        "ChatStatus" to "io.opaa.chat.ChatStatus",
        "ChatRole" to "io.opaa.chat.ChatRole",
        "ColorScheme" to "io.opaa.branding.ColorScheme",
    ))
}

tasks.named<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("openApiGenerate") {
    // Local copy inside the configure block on purpose: the doLast action must not reference
    // script-level members (layout, file(...), top-level vals — those are fields of the script
    // class), or the closure drags the whole build script into the configuration cache, which
    // Gradle rejects ("cannot serialize Gradle script object references").
    val generatedDtoDir = project.layout.buildDirectory.dir("generated/openapi/src/main/java/io/opaa/api/dto")
    doLast {
        // Remove generated enum files that are mapped to existing domain enums via typeMappings.
        // The generator still creates these files even with typeMappings configured.
        val generatedDir = generatedDtoDir.get().asFile
        listOf("SpaceRole.java", "SpaceKind.java", "SpaceVisibility.java", "SystemRole.java", "GroupKind.java", "DirectorySyncOutcome.java", "LibraryOwnerType.java", "LibraryVisibility.java", "DocumentStatus.java", "DocumentSourceType.java", "AssetRole.java", "PermissionSubjectType.java", "ActorKind.java", "AuditSubjectKind.java", "AuditOutcome.java", "AuditObjectType.java", "AuditEventType.java", "AuditIncidentScopePurpose.java", "AuditIncidentScopeStatus.java", "ChatStatus.java", "ChatRole.java", "ColorScheme.java").forEach { fileName ->
            File(generatedDir, fileName).delete()
        }
    }
}

tasks.named("compileJava") {
    dependsOn("openApiGenerate")
}
