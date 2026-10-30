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
import org.springframework.test.context.TestExecutionListeners;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The {@code oidc} application on a real port with the actuator on a management port of its own
 * ({@code management.server.port=0}, a random one), as the Helm chart runs it.
 *
 * <p><b>Why this cannot be another signature:</b> a separate management port starts a child context
 * with its own web server, which only exists next to a real server ({@code RANDOM_PORT}, not the
 * MockMvc environment of {@link OpaaLocalAuthMockMvcTest}), and it takes every actuator path off
 * the API port - the classes of the other signatures that read health or info through MockMvc would
 * no longer find them. Only {@code oidc} enforces a sign-in, so {@link OpaaIntegrationTest} ({@code
 * local,dev}, also on a real port) cannot show that an anonymous request is refused.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "opaa.auth.local.jwt-secret=" + OpaaLocalAuthMockMvcTest.JWT_SECRET,
      "opaa.directory-sync.schedule-enabled=false",
      "spring.ai.openai.base-url=" + OpaaIntegrationTest.CHAT_BASE_URL,
      "spring.ai.openai.embedding.base-url=" + OpaaIntegrationTest.EMBEDDING_BASE_URL
    })
@Import({TestcontainersConfiguration.class, FakeEmbeddingEndpoint.class, TestInstallation.class})
@ActiveProfiles("oidc")
@Testcontainers(disabledWithoutDocker = true)
@TestExecutionListeners(
    listeners = {LeftoverRowGuard.class, SeededRowRestorer.class},
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface OpaaManagementPortTest {}
