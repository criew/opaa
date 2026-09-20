/**
 * Integration tests against a <em>real</em> Keycloak started in Docker (#1817, ADR-0036
 * Entscheidung 3) - the acceptance level above the common test double ({@code
 * io.opaa.group.sync.keycloak.FakeKeycloakServer}), which stays the contract level.
 *
 * <p><b>Runs only via {@code ./gradlew keycloakIntegrationTest}</b>, never as part of {@code
 * build}/{@code test}. Measured on the reference machine, a {@code start-dev} Keycloak 26.7 needs
 * about 46 seconds to answer and about 720 MiB - five times the footprint of the MinIO fixture that
 * does run inside {@code test}, and enough to matter on a developer machine and in each of the CI
 * shards. Unlike the Confluence suite (ADR-0023) it needs neither internet access nor a licence, so
 * it has no environment-variable gate either: Docker is the only precondition, and the tests skip
 * without it. CI runs the suite nightly and on demand ({@code
 * .github/workflows/keycloak-integration.yml}).
 *
 * <p>{@link io.opaa.integration.keycloak.KeycloakFixture} starts one Keycloak per JVM and seeds a
 * realm with a group tree, users and a service account holding exactly {@code view-users} and
 * {@code query-groups}. It addresses the container over {@code 127.0.0.1}: that is the host the
 * shared test signature allowlists for the sign-in's address policy, so this suite needs a local
 * docker daemon rather than a remote one.
 */
package io.opaa.integration.keycloak;
