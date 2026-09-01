plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
    alias(libs.plugins.cyclonedx.bom)
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
    // OpenAPI spec, generator config and shared domain enums (issue #896) - transitively brings
    // the generated io.opaa.api.dto classes onto this module's compile classpath via
    // opaa-api's own main sourceSet output, without recompiling them here.
    implementation(project(":opaa-api"))
    implementation(libs.bundles.spring.boot)
    implementation(libs.bundles.spring.ai)
    implementation(libs.spring.boot.starter.liquibase)
    implementation(libs.caffeine)
    implementation(libs.jtokkit)
    implementation(libs.jsoup)
    implementation(libs.tika.core)
    implementation(libs.pgvector)
    implementation(libs.poi.ooxml)
    implementation(libs.commons.csv)
    runtimeOnly(libs.bundles.runtime)
    testImplementation(libs.bundles.test.deps)
    testRuntimeOnly(libs.bundles.test.runtime.deps)
}

// CycloneDX plugin 3.x: each project gets its own "cyclonedxDirectBom" task (scans that
// project's configurations), and the root "cyclonedxBom" task aggregates all of them - so
// includeConfigs is set per project here, restricting every SBOM to runtimeClasspath (what
// ships, not the test/build toolchain). Retrieval: docs/sbom.md.
allprojects {
    tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
        includeConfigs.set(listOf("runtimeClasspath"))
    }
}

tasks.cyclonedxBom {
    componentName.set("opaa-backend")
}

// Registers the evaluateXRetrieval/checkXRetrievalBaseline task pair for one eval domain (issue
// #835 — this used to be ~130 hand-copied lines per domain; see git history for the original,
// per-domain comments this collapses). Both tasks share identical Test wiring across domains;
// only the task-name suffix, descriptions, and the domain's own harness/baseline test classes
// vary between calls.
//
// Not wired into `check`/`build`/`test` on purpose (see the `evalTest` source set comment above)
// — invoke explicitly, e.g. `./gradlew evaluateRetrieval`. Needs Docker; the evaluate task
// downloads the `nomic-embed-text` model into the Ollama Testcontainer on first run.
//
// The evaluate task produces the report, and only the report: the baseline test is never part of
// it, because that test consumes a report the evaluate task itself writes (issue #414) — included,
// JUnit would run it alphabetically before the harness test, i.e. before the report file exists,
// failing its "No report found" guard before the check task ever gets a turn.
//
// Filtering by the harness/baseline test's fully qualified class name (rather than a "*Suffix"
// wildcard) sidesteps the wildcard trap that originally motivated this refactor (issue #234): a
// wildcard like "*RetrievalEvaluationHarnessTest" also matches
// "CityLandmarksRetrievalEvaluationHarnessTest" because it ends with that suffix, which would
// silently double the runtime and couple both domains' runs together.
//
// The check task runs BOTH baseline tests of the domain (raw-vector and pipeline path, issue
// #1040). JUnit runs both classes regardless of the other's outcome, so each path gets its own
// verdict and its own delta table — a red pipeline path never suppresses the raw-vector judgment
// and vice versa, which is the reason they are two test classes rather than one.
//
// `pipelineBaselineTestClass` is null for a domain whose pipeline baseline has not been drawn yet:
// wiring the test without a committed baseline would turn the nightly job red for a measurement
// that was never taken. See the call sites for which domain that currently is and under which
// issue it is being drawn.
fun registerEvalDomain(
    name: String,
    evaluateDescription: String,
    checkDescription: String,
    harnessTestClass: String,
    baselineTestClass: String,
    pipelineBaselineTestClass: String?,
) {
    val evaluateTaskName = "evaluate${name}Retrieval"
    tasks.register<Test>(evaluateTaskName) {
        description = evaluateDescription
        group = "verification"
        testClassesDirs = sourceSets["evalTest"].output.classesDirs
        classpath = sourceSets["evalTest"].runtimeClasspath
        useJUnitPlatform()
        filter {
            includeTestsMatching(harnessTestClass)
        }
        outputs.upToDateWhen { false }
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        systemProperty("file.encoding", "UTF-8")
        // Gradle does not forward -D command-line system properties into a forked Test JVM on its
        // own (they stay properties of the Gradle daemon process that evaluates this build script) —
        // every property a harness class reads via System.getProperty/Boolean.getBoolean at runtime
        // needs an explicit systemProperty() call here, read from this daemon-process property at
        // configuration time. opaa.eval.allowGpu (RetrievalEvaluationHarnessTest, local GPU opt-out),
        // opaa.eval.ollamaBaseUrl (issue #1076, external Ollama endpoint) and the issue #1041
        // variant-comparison opt-in share this list because all three are optional, manually-invoked
        // knobs rather than something every eval domain always needs.
        listOf(
            "opaa.eval.allowGpu",
            "opaa.eval.ollamaBaseUrl",
            "opaa.eval.runVariantComparison",
            "opaa.eval.variantComparisonFile",
        ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
        testLogging {
            events("passed", "skipped", "failed", "standard_out")
            showStandardStreams = true
        }
    }

    // Depends on the evaluate task above so a single `./gradlew check${name}RetrievalBaseline`
    // invocation (as used by the nightly/manual/label-triggered CI job in
    // .github/workflows/retrieval-regression.yml) runs the full Docker-requiring harness and then
    // the baseline comparison, in order. Not part of `check`/`build`/`evalUnitTest` — same
    // rationale as the evaluate task itself.
    tasks.register<Test>("check${name}RetrievalBaseline") {
        description = checkDescription
        group = "verification"
        dependsOn(evaluateTaskName)
        testClassesDirs = sourceSets["evalTest"].output.classesDirs
        classpath = sourceSets["evalTest"].runtimeClasspath
        useJUnitPlatform()
        outputs.upToDateWhen { false }
        filter {
            includeTestsMatching(baselineTestClass)
            if (pipelineBaselineTestClass != null) {
                includeTestsMatching(pipelineBaselineTestClass)
            }
        }
        testLogging {
            events("passed", "skipped", "failed", "standard_out")
            showStandardStreams = true
        }
    }
}

// comic-characters domain (issue #227/#228).
registerEvalDomain(
    name = "",
    evaluateDescription = "Runs the retrieval-quality evaluation harness (Hit Rate, MRR, nDCG, Recall) " +
        "against eval/corpus using Testcontainers (pgvector + Ollama). Not part of build/check.",
    checkDescription = "Runs evaluateRetrieval, then fails if the result regresses beyond tolerance " +
        "against eval/baseline/comic-characters.json (raw-vector path, issue #228) or " +
        "eval/baseline/pipeline-comic-characters.json (pipeline path, issue #1040). Needs Docker.",
    harnessTestClass = "io.opaa.eval.RetrievalEvaluationHarnessTest",
    baselineTestClass = "io.opaa.eval.BaselineRegressionTest",
    pipelineBaselineTestClass = "io.opaa.eval.PipelineBaselineRegressionTest",
)

// city-landmarks domain (issue #234): second domain, second test class pair — see
// CityLandmarksRetrievalEvaluationHarnessTest's Javadoc for why it is a near-duplicate rather than
// a parameterization of RetrievalEvaluationHarnessTest. No shared report file, no shared baseline,
// no shared group with the comic-characters domain (issue #234 acceptance criterion "keine
// gemeinsame overall-Gruppe mit Comichelden").
registerEvalDomain(
    name = "CityLandmarks",
    evaluateDescription = "Runs the retrieval-quality evaluation harness for the city-landmarks domain " +
        "against eval/corpus/city-landmarks using Testcontainers (pgvector + Ollama). Not part " +
        "of build/check.",
    checkDescription = "Runs evaluateCityLandmarksRetrieval, then fails if the result regresses " +
        "beyond tolerance against eval/baseline/city-landmarks.json (raw-vector path, issue #234) " +
        "or eval/baseline/pipeline-city-landmarks.json (pipeline path, issue #1081). Needs Docker.",
    harnessTestClass = "io.opaa.eval.CityLandmarksRetrievalEvaluationHarnessTest",
    baselineTestClass = "io.opaa.eval.CityLandmarksBaselineRegressionTest",
    pipelineBaselineTestClass = "io.opaa.eval.CityLandmarksPipelineBaselineRegressionTest",
)

// verwaltung domain (issues #1042/#1043): third domain, the one that carries the five named
// Golden-Fall-Klassen of docs/features/retrieval-benchmark.md §5. Both paths are gated from the
// start — this domain's raw-vector and pipeline baselines were drawn in the same run as part of
// #1043, so neither path needs the ungated interim state city-landmarks is still in.
registerEvalDomain(
    name = "Verwaltung",
    evaluateDescription = "Runs the retrieval-quality evaluation harness for the verwaltung domain " +
        "against eval/corpus/verwaltung using Testcontainers (pgvector + Ollama). Not part of " +
        "build/check.",
    checkDescription = "Runs evaluateVerwaltungRetrieval, then fails if the result regresses beyond " +
        "tolerance against eval/baseline/verwaltung.json (raw-vector path) or " +
        "eval/baseline/pipeline-verwaltung.json (pipeline path), issue #1043. Needs Docker.",
    harnessTestClass = "io.opaa.eval.VerwaltungRetrievalEvaluationHarnessTest",
    baselineTestClass = "io.opaa.eval.VerwaltungBaselineRegressionTest",
    pipelineBaselineTestClass = "io.opaa.eval.VerwaltungPipelineBaselineRegressionTest",
)

// Fast, Docker-free unit tests for the pure metric math (RetrievalMetrics, MetricsAggregate,
// CorpusManifest, BaselineComparator — see their Javadoc). Lives in the evalTest source set (not
// `main`/`test`) so the classes under test never ship in the production jar, but still runs as
// part of `check` so a Spring AI/Testcontainers upgrade that breaks compilation, or a metric-math
// regression, is caught without Docker. Explicitly excludes RetrievalEvaluationHarnessTest (needs
// Testcontainers, stays exclusive to `evaluateRetrieval`) and BaselineRegressionTest (needs a
// report file that only exists after a real `evaluateRetrieval` run, stays exclusive to
// `checkRetrievalBaseline` below) — issue #227/#228's exclusion criterion is about those two
// specific test classes, not about the evalTest source set as a whole. The `*BaselineRegressionTest`
// pattern covers the pipeline path's baseline tests (issue #1040) for the identical reason: they
// consume a report file no Docker-free build produces.
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
    // Never cache or skip: whether the tests actually run depends on OPAA_OPENAI_API_KEY (a
    // JUnit @EnabledIfEnvironmentVariable condition, invisible to Gradle's input tracking) and
    // on the live OpenAI API. A cached "success" from a key-less run would otherwise satisfy a
    // later keyed run without ever contacting OpenAI. Same pattern as evaluateRetrieval.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
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
    // maxHeapSize is a per-worker-process ceiling, not a suite-wide one: with maxParallelForks = 2
    // below (CI-only), two Gradle test-worker JVMs run concurrently, each capped at this size and
    // each building its own copy of every shared Spring context and Testcontainers container from
    // scratch - up to 4g combined heap plus a doubled container set, exactly the OOM axis the
    // comment above this block describes. That is headroom this ceiling already has to cover on
    // the constrained CI runner it targets; if CI heap pressure shows up again, prefer lowering
    // this value (e.g. to "1500m") over raising it, since raising it multiplies by the fork count.
    maxHeapSize = "2g"

    // Issue #497, measure 4 (CI-only): forking two Gradle test-worker JVMs (each with its own
    // Testcontainers container) was measured on a local Windows dev machine and rejected there -
    // three runs swung between 4:30 and 6:42 min, worse than the 4:50 min single-fork baseline in
    // two of three runs, because the second worker competes with the first for CPU/Docker
    // resources already under pressure from everything else running on that machine (see PR #499).
    // Dedicated CI runners do not share that contention the same way, so this stays opt-in via the
    // `CI` environment variable GitHub Actions sets on every job - local `./gradlew test` runs
    // single-forked exactly as before, and this is a one-line revert if CI turns out unstable too.
    // Uses the environment-variable value provider (not System.getenv) so this stays
    // configuration-cache-safe: Gradle can track it as a build input instead of silently missing
    // that the task's behavior depends on it.
    if (providers.environmentVariable("CI").isPresent) {
        maxParallelForks = 2
    }
}

// The OpenAPI spec, the generator config (typeMappings/importMappings/doLast cleanup) and the
// generated io.opaa.api.dto classes all live in :opaa-api now (issue #896) - this module only
// consumes that project's main sourceSet output via `implementation(project(":opaa-api"))` above.
