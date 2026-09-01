package io.opaa.test;

import io.opaa.TestcontainersConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Canonical context signature for service/repository-level integration tests that need a real
 * Postgres but no MockMvc/web layer: {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}, the
 * shared {@link TestcontainersConfiguration} container, and {@code @ActiveProfiles({"local",
 * "dev"})} - the same profile combination {@code SPRING_PROFILES_ACTIVE=local,dev ./gradlew
 * bootRun} uses (AGENTS.md), so this starts the context the way the application is actually run.
 *
 * <p>Issue #843: every class carrying this exact signature shares one Spring context and one
 * Testcontainers Postgres instance instead of paying for its own - a class that additionally
 * declares its own {@code @DynamicPropertySource} or {@code @MockitoBean} set still gets its own
 * context regardless (Spring's cache key includes both), so such classes stay on this
 * meta-annotation but keep a short comment explaining why they cannot share.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Long enough that FullTextBackfillScheduler's tick (#1047) never fires during a test run -
    // it would otherwise race a test's own assertions against chunk_full_text/vector_store in the
    // shared context this signature provides.
    properties = "opaa.indexing.full-text-backfill.tick-ms=3600000")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
public @interface OpaaIntegrationTest {}
