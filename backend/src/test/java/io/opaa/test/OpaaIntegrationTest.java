package io.opaa.test;

import io.opaa.TestcontainersConfiguration;
import io.opaa.auth.UserRepository;
import io.opaa.chat.ChatMessageRepository;
import io.opaa.group.sync.DirectorySyncStatusRecorder;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The one context signature of the backend suite (AGENTS.md, "Spring-Testkontexte", #1481): a real
 * Postgres, the full application on a random port, MockMvc, the fixed test properties below and the
 * shared test beans of {@link OpaaTestBeans}. Every class carrying it shares one Spring context and
 * one Testcontainers Postgres.
 *
 * <p><b>A class that adds any own differentiator leaves this context</b> - an own
 * {@code @DynamicPropertySource}, {@code @TestPropertySource}, {@code @Import},
 * {@code @MockitoBean}/{@code @MockitoSpyBean}/{@code @TestBean} or nested
 * {@code @TestConfiguration} all enter Spring's cache key and cost another application start and
 * another container. Constants belong in the {@code properties} below, runtime-resolved values in
 * an {@link ContextConfiguration#initializers() initializer} of this annotation, replaced beans in
 * {@link OpaaTestBeans}. Where none of that works, one of the three named exception signatures
 * applies; {@code SpringContextSignatureTest} holds that list and fails on any further one.
 *
 * <p><b>{@code ActiveChatModelResolver} is the real one here</b>, unlike under the indexing
 * signature this replaced: a class that needs a scripted chat answer carries {@link
 * OpaaMockedChatModelIntegrationTest} instead. The indexing classes never script one - their
 * resolver call, if any, fails against the unreachable default endpoint and takes the same fallback
 * path an unstubbed mock produced before.
 *
 * <p><b>One database for the whole suite.</b> A class wipes in its own {@code @BeforeEach} what it
 * touches, cleans up after itself in {@code @AfterEach}, and never asserts against an unfiltered
 * table - only against rows scoped to ids it created itself. {@link LeftoverGrantGuard} names the
 * offending class for the two tables no cleanup chain covers.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // Chunking small enough that a short fixture yields several chunks.
      "opaa.indexing.chunk-size=100",
      "opaa.indexing.chunk-overlap=10",
      "opaa.indexing.batch-size=10",
      // The two hosts the OIDC tests' local test doubles listen on; no class asserts that an
      // allowlisted OIDC host is refused.
      "opaa.auth.oidc.target-validation.allowlist=idp.example,127.0.0.1",
      // No class asserts a 429. Enabled, the singleton counters would instead accumulate across
      // every class of this shared context and produce one where a standalone run showed none.
      "opaa.rate-limit.enabled=false",
      // Small enough for the "upload too large" refusals; every other fixture is far below it.
      "opaa.upload.max-file-size=4096",
      // Only read by the startup ApplicationRunner, which finds no rows at that point.
      "opaa.upload.pending-recovery-threshold-minutes=1",
      // {@link io.opaa.FakeEmbeddingModel} gives every text the same vector, so every row of
      // vector_store ties. An HNSW scan would hand back an arbitrary slice of ef_search (40 by
      // default) candidates that the metadata filter then thins out to its share - which turns
      // recall into a test oracle and makes a "found all of my own chunks" assertion depend on how
      // many foreign rows the table happens to hold. At the pgvector maximum the scan is exact for
      // any table size a test produces.
      "spring.datasource.hikari.connection-init-sql=SET hnsw.ef_search = 1000"
    })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OpaaTestBeans.class})
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
@ContextConfiguration(
    initializers = {OpaaTestPathInitializer.class, OpaaTestTargetAllowlistInitializer.class})
// Spies, not mocks: they delegate to the real bean, so a class that does not stub them sees
// production behaviour. Declared here rather than per class because a class-local declaration would
// split the context; Spring resets them after every test method.
@MockitoSpyBean(
    types = {UserRepository.class, ChatMessageRepository.class, DirectorySyncStatusRecorder.class})
// A derived signature must not declare @TestExecutionListeners of its own: like
// @ContextConfiguration, it is resolved by nearest declaration, so both listeners below would
// silently disappear there.
@TestExecutionListeners(
    listeners = {OpaaTestBeanResetListener.class, LeftoverGrantGuard.class},
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface OpaaIntegrationTest {}
