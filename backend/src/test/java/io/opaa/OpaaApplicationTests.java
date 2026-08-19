package io.opaa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke-tests that the Spring context starts at all. Deliberately uses the exact same annotation
 * signature - {@code @SpringBootTest(webEnvironment = RANDOM_PORT)},
 * {@code @Import(TestcontainersConfiguration.class)}, {@code @ActiveProfiles({"local", "dev"})} -
 * as the shared-context integration test group (e.g. {@code SpaceServiceIntegrationTest}), instead
 * of {@code @ActiveProfiles("dev")} alone (issue #497): Spring Boot's test context cache keys on
 * the exact merged context configuration, so a differing profile set here forced a second,
 * otherwise redundant Spring context (and its own Testcontainers Postgres instance) to be built
 * just for this one no-op test. {@code local} is not a narrower assertion being dropped - it is the
 * same profile combination {@code SPRING_PROFILES_ACTIVE=local,dev ./gradlew bootRun} uses in
 * AGENTS.md's own Build &amp; Test section, so this test now starts the context the way the
 * application is actually run, not a variant of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class OpaaApplicationTests {

  @Test
  void contextLoads() {}
}
