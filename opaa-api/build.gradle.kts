plugins {
    java
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.spotless)
}

group = "io.opaa"
version = "0.0.1-SNAPSHOT"
description = "OPAA OpenAPI spec, generator config and shared domain enums"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// The generated DTOs live in this module's own build directory and are never checked in - same
// rationale as backend/build.gradle.kts before the module split (issue #896). Consuming modules
// (currently only :backend) get them transitively through this module's main sourceSet, so a
// spec-only change now only invalidates this module's compileJava/openApiGenerate, not the whole
// backend (issue #826 T2 / #896's whole point).
sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
    }
}

dependencies {
    // The generated io.opaa.api.dto classes (spring generator) reference these annotation types
    // regardless of the domain enums themselves being Spring-free - see the version catalog
    // comment on jackson-annotations for why this is not a Spring Boot dependency.
    implementation(libs.bundles.opaa.api.generated.dto.deps)

    // Deliberately Spring-free (issue #896 leitplanke): plain JUnit Jupiter/AssertJ plus
    // SnakeYAML for the parity tests' direct spec parsing, not the Spring Boot-managed
    // test-deps bundle backend/build.gradle.kts uses.
    testImplementation(libs.bundles.opaa.api.test.deps)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.opaa.api.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
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

// Deutsche Texte in Quellen und Ressourcen sind UTF-8 - siehe backend/build.gradle.kts fuer die
// ausfuehrliche Begruendung (Windows-Plattform-Encoding faelscht sonst Umlaute).
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
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
        "NotificationType" to "NotificationType",
    ))
    importMappings.set(mapOf(
        "Instant" to "java.time.Instant",
        "SpaceRole" to "io.opaa.api.types.SpaceRole",
        "SpaceVisibility" to "io.opaa.api.types.SpaceVisibility",
        "SystemRole" to "io.opaa.api.types.SystemRole",
        "GroupKind" to "io.opaa.api.types.GroupKind",
        "DirectorySyncOutcome" to "io.opaa.api.types.DirectorySyncOutcome",
        "LibraryOwnerType" to "io.opaa.api.types.LibraryOwnerType",
        "LibraryVisibility" to "io.opaa.api.types.LibraryVisibility",
        "DocumentStatus" to "io.opaa.api.types.DocumentStatus",
        "DocumentSourceType" to "io.opaa.api.types.DocumentSourceType",
        "AssetRole" to "io.opaa.api.types.AssetRole",
        "PermissionSubjectType" to "io.opaa.api.types.PermissionSubjectType",
        "ActorKind" to "io.opaa.api.types.ActorKind",
        "AuditSubjectKind" to "io.opaa.api.types.AuditSubjectKind",
        "AuditOutcome" to "io.opaa.api.types.AuditOutcome",
        "AuditObjectType" to "io.opaa.api.types.AuditObjectType",
        "AuditEventType" to "io.opaa.api.types.AuditEventType",
        "AuditIncidentScopePurpose" to "io.opaa.api.types.AuditIncidentScopePurpose",
        "AuditIncidentScopeStatus" to "io.opaa.api.types.AuditIncidentScopeStatus",
        "ChatStatus" to "io.opaa.api.types.ChatStatus",
        "ChatRole" to "io.opaa.api.types.ChatRole",
        "ColorScheme" to "io.opaa.api.types.ColorScheme",
        "NotificationType" to "io.opaa.api.types.NotificationType",
    ))
}

tasks.named<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("openApiGenerate") {
    // Local copies inside the configure block on purpose: the doLast action must not reference
    // script-level members (layout, file(...), top-level vals - those are fields of the script
    // class), or the closure drags the whole build script into the configuration cache, which
    // Gradle rejects ("cannot serialize Gradle script object references"). See issue #835/#857 for
    // the mechanical derivation this replaced a hand-maintained list with; moved here unchanged as
    // part of #896's module split.
    val generatedDtoDir = project.layout.buildDirectory.dir("generated/openapi/src/main/java/io/opaa/api/dto")
    val generatedEnumFileNames =
        typeMappings.map { mappings ->
            (mappings.keys + mappings.values)
                .filter { it != "DateTime" && it != "Instant" }
                .map { "$it.java" }
        }
    doLast {
        val generatedDir = generatedDtoDir.get().asFile
        generatedEnumFileNames.get().forEach { fileName -> File(generatedDir, fileName).delete() }
    }
}

tasks.named("compileJava") {
    dependsOn("openApiGenerate")
}
