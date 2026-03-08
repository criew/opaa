# ADR-0006: OpenAPI-First DTO Generation

## Status

Accepted

## Context

OPAA exposes a REST API defined by an OpenAPI specification (`backend/src/main/resources/openapi/opaa-api.yaml`). Initially, backend DTOs were handwritten Java records in `io.opaa.api.dto`. As the API grew (query, indexing, workspace endpoints), keeping handwritten DTOs in sync with the OpenAPI spec became error-prone and duplicated effort.

PR #134 introduced OpenAPI code generation for non-workspace DTOs. Workspace DTOs remained handwritten due to their dependency on domain enums (`WorkspaceRole`, `WorkspaceType`). This inconsistency — some DTOs generated, some handwritten — created confusion about the source of truth.

The frontend already generates all TypeScript types from the same OpenAPI spec via `openapi-typescript`, making the spec the de facto contract.

## Decision

**All API DTOs MUST be generated from the OpenAPI specification.** No handwritten DTO classes are allowed in `io.opaa.api.dto`.

Specifically:

1. **The OpenAPI spec is the single source of truth** for all request/response schemas. Changes to the API contract start with a spec change.
2. **Backend DTOs are generated** by the OpenAPI Generator Gradle plugin (`spring` generator) into `build/generated/openapi/`. Generated code is not checked into version control.
3. **Frontend types are generated** by `openapi-typescript` into `frontend/src/types/generated/`. Generated code is not checked into version control.
4. **Domain enums referenced by DTOs** (e.g., `WorkspaceRole`, `WorkspaceType`) are mapped via `typeMappings`/`importMappings` in `build.gradle.kts` so that generated DTOs use the existing domain types directly — no conversion layer needed.
5. **New API endpoints** must first define their schemas in the OpenAPI spec, then use the generated DTOs in controllers and services.

### Configuration

The OpenAPI Generator is configured in `backend/build.gradle.kts`:

- `models` set to `""` (generate all schemas)
- `typeMappings` maps domain enums and custom types to existing classes
- `importMappings` provides the fully qualified class names for mapped types
- A `doLast` block removes generated enum files that are mapped to domain enums (the generator creates them despite `typeMappings`)

## Consequences

### Easier

- **Consistency guaranteed:** DTOs always match the spec — no drift possible
- **Less boilerplate:** No need to write records, getters, equals/hashCode, or Jackson annotations
- **Single workflow:** Change the spec → regenerate → adapt service code
- **Frontend-backend alignment:** Both sides generate from the same spec

### More difficult

- **Generated code style:** Generated DTOs are mutable POJOs with getters/setters instead of concise Java records. Service code uses `request.getName()` instead of `request.name()`.
- **Build dependency:** `compileJava` depends on `openApiGenerate`; the spec must be valid for the build to succeed
- **Enum mapping maintenance:** When adding new domain enums used in the API, `typeMappings` and `importMappings` must be updated in `build.gradle.kts`, and the corresponding generated file must be added to the `doLast` cleanup block
