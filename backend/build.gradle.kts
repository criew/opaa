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
}

dependencies {
    implementation(libs.bundles.spring.boot)
    implementation(libs.bundles.spring.ai)
    implementation(libs.liquibase.core)
    implementation(libs.caffeine)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.bundles.jjwt.runtime)
    runtimeOnly(libs.bundles.runtime)
    testImplementation(libs.bundles.test.deps)
    testRuntimeOnly(libs.bundles.test.runtime.deps)
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

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
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
        "WorkspaceRole" to "WorkspaceRole",
        "WorkspaceType" to "WorkspaceType",
        "SystemRole" to "SystemRole",
    ))
    importMappings.set(mapOf(
        "Instant" to "java.time.Instant",
        "WorkspaceRole" to "io.opaa.workspace.WorkspaceRole",
        "WorkspaceType" to "io.opaa.workspace.WorkspaceType",
        "SystemRole" to "io.opaa.auth.SystemRole",
    ))
}

tasks.named<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("openApiGenerate") {
    doLast {
        // Remove generated enum files that are mapped to existing domain enums via typeMappings.
        // The generator still creates these files even with typeMappings configured.
        val generatedDir = layout.buildDirectory.dir("generated/openapi/src/main/java/io/opaa/api/dto").get().asFile
        listOf("WorkspaceRole.java", "WorkspaceType.java", "SystemRole.java").forEach { fileName ->
            file("$generatedDir/$fileName").delete()
        }
    }
}

tasks.named("compileJava") {
    dependsOn("openApiGenerate")
}
