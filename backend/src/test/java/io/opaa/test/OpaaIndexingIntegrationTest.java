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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Third canonical context signature (AGENTS.md "Spring-Testkontexte"), for the indexing pipeline's
 * integration tests: the same base as {@link OpaaIntegrationTest} (real Postgres, {@code
 * webEnvironment = RANDOM_PORT}, {@code @ActiveProfiles({"local", "dev"})}) plus the fixed chunking
 * properties and mocked {@code ChatModel}/{@code ActiveChatModelResolver}/{@code EmbeddingModel}
 * every indexing test needs - declared independently rather than layered on {@link
 * OpaaIntegrationTest}, because only one {@code @SpringBootTest} declaration can contribute the
 * {@code properties} attribute this signature also needs (mirrors {@link OpaaMockMvcTest}, which is
 * independent of {@link OpaaIntegrationTest} for the same reason).
 *
 * <p>{@code opaa.indexing.filesystem-allowlist} points at one process-wide base directory ({@link
 * OpaaIndexingTestDirectory#BASE_DIR}), registered once by {@link
 * OpaaIndexingFilesystemAllowlistInitializer} - not a per-class
 * {@code @TempDir}/{@code @DynamicPropertySource}, which would key each class to its own context
 * regardless of this shared meta-annotation (see {@link OpaaIntegrationTest}'s Javadoc). A test
 * creates its own subdirectory under that base via {@link
 * OpaaIndexingTestDirectory#subdirectory(String)}, but that subdirectory is a filesystem
 * convenience, not a data-isolation boundary: every class carrying this signature shares one
 * database against one Spring context, so a class's own {@code @BeforeEach} must wipe the shared
 * tables it touches (e.g. {@code TRUNCATE TABLE vector_store}, {@code
 * documentRepository.deleteAll()}) exactly as it already does today, and must never assert against
 * an unfiltered table - only against rows scoped to the library/document ids it created itself.
 *
 * <p>{@link OpaaIndexingMockConfiguration} supplies the shared {@code ChatModel}/{@code
 * ActiveChatModelResolver}/{@code EmbeddingModel} beans; {@link OpaaIndexingMockResetListener}
 * resets the two mocks before every test method so stubbing never leaks between classes sharing
 * this context. A class with its own additional {@code @MockitoBean}/{@code @MockitoSpyBean} or
 * {@code @TestBean} override still gets its own context regardless (Spring's cache key includes
 * both) - such classes stay on this meta-annotation but keep a short comment explaining why they
 * cannot share, exactly as for {@link OpaaIntegrationTest}.
 *
 * <p>Deliberately no {@code properties()} attribute with {@code @AliasFor(annotation =
 * SpringBootTest.class, attribute = "properties")} the way {@link OpaaMockMvcTest} has one:
 * {@code @AliasFor} on an array attribute replaces the whole array rather than appending to it, so
 * a class supplying its own {@code properties()} override would silently lose the three chunking
 * properties this signature already fixes.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "opaa.indexing.chunk-size=100",
      "opaa.indexing.chunk-overlap=10",
      "opaa.indexing.batch-size=10",
      // Long enough that FullTextBackfillScheduler's tick (#1047) never fires during a test run -
      // it would otherwise race a test's own assertions against chunk_full_text/vector_store in
      // the shared context this signature provides.
      "opaa.indexing.full-text-backfill.tick-ms=3600000"
    })
@Import({TestcontainersConfiguration.class, OpaaIndexingMockConfiguration.class})
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
@ContextConfiguration(initializers = OpaaIndexingFilesystemAllowlistInitializer.class)
@TestExecutionListeners(
    listeners = OpaaIndexingMockResetListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface OpaaIndexingIntegrationTest {}
