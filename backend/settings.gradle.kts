rootProject.name = "backend"

// opaa-api (issue #896): the OpenAPI spec, the Java generator config (incl. the typeMappings
// mechanics from #857) and the shared domain enums the typeMappings point at live in their own
// module so a spec-only change invalidates just that module, not backend's whole 240+-class
// sourceSet. Its directory is a top-level sibling of backend/ (not nested under it), but the
// Gradle root stays here: the wrapper, CI's `working-directory: backend/`, and the Docker/cache
// setup all already assume backend/ is the Gradle invocation root, and none of that needs to move
// just because a second module joined the build.
include(":opaa-api")
project(":opaa-api").projectDir = file("../opaa-api")
