/**
 * Integration tests against a <em>real</em> Confluence Data Center started in Docker (ADR-0023,
 * #1171) - the acceptance level above the common test double ({@code
 * io.opaa.indexing.source.confluence.FakeConfluenceServer}), which stays the contract level for
 * both editions; Cloud cannot be containerised and is covered by the double alone.
 *
 * <p>Runs only via {@code ./gradlew confluenceIntegrationTest} (never part of {@code build}/{@code
 * test}): it needs Docker and internet access for the public three-hour time-bomb licence Atlassian
 * publishes for testing. {@link io.opaa.integration.confluence.ConfluenceDataCenterFixture} starts
 * Postgres and Confluence once per JVM, walks the setup wizard over plain HTTP, creates the admin
 * account, two personal access tokens with different space rights and a defined set of spaces,
 * pages and attachments; every test class in this package builds on that fixture. CI runs the suite
 * nightly and on demand ({@code .github/workflows/confluence-integration.yml}).
 */
package io.opaa.integration.confluence;
