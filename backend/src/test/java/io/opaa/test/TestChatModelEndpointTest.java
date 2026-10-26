package io.opaa.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.support.TestPropertySourceUtils;

/**
 * No context of the suite may reach a real model over the network. The test database installs
 * itself the way the {@code dev} mode does, with {@code spring.ai.openai.base-url} as the address
 * of its active chat model (ADR-0060). Left at the default, that is a locally running Ollama, which
 * retrieval (sub-question decomposition) then actually calls; the same holds for the embedding
 * endpoint wherever the real embedding client is in place.
 *
 * <p>Inline test properties outrank {@code application.yml} and OS environment variables, so an
 * entry in a signature's own properties decides the address regardless of the developer's shell. A
 * host under {@code .invalid} (RFC 6761) never resolves, so a call fails at once, as it does in CI.
 * Built from {@code MergedContextConfiguration} alone: no context, no Docker.
 */
class TestChatModelEndpointTest {

  private static final String CHAT_BASE_URL = "spring.ai.openai.base-url";
  private static final String EMBEDDING_BASE_URL = "spring.ai.openai.embedding.base-url";

  static Stream<Class<?>> signatureCarriers() throws ClassNotFoundException {
    // The signature annotations resolve like a class carrying them; the two named classes run the
    // retrieval pipeline with sub-question decomposition against the seeded model.
    return Stream.concat(
        SpringContextSignatureTest.CANONICAL_SIGNATURES.stream(),
        Stream.of(
            Class.forName("io.opaa.searchadmin.SearchDiagnosisIntegrationTest"),
            Class.forName("io.opaa.searchadmin.SearchStatusIntegrationTest")));
  }

  @ParameterizedTest
  @MethodSource("signatureCarriers")
  void theInstalledChatModelPointsAtAnAddressThatNeverAnswers(Class<?> carrier) {
    Map<String, Object> inlined = inlinedProperties(carrier);

    assertThat(inlined)
        .as(
            "%s must set %s itself, otherwise the installed chat model is the application.yml"
                + " default and a locally running model server receives real chat requests",
            carrier.getSimpleName(), CHAT_BASE_URL)
        .containsKey(CHAT_BASE_URL);
    assertThat(hostOf(inlined, CHAT_BASE_URL))
        .as("host of %s in %s", CHAT_BASE_URL, carrier.getSimpleName())
        .endsWith(".invalid");
  }

  @ParameterizedTest
  @MethodSource("signatureCarriers")
  void theEmbeddingEndpointPointsAtAnAddressThatNeverAnswers(Class<?> carrier) {
    Map<String, Object> inlined = inlinedProperties(carrier);

    assertThat(inlined)
        .as("%s must set %s itself", carrier.getSimpleName(), EMBEDDING_BASE_URL)
        .containsKey(EMBEDDING_BASE_URL);
    assertThat(hostOf(inlined, EMBEDDING_BASE_URL))
        .as("host of %s in %s", EMBEDDING_BASE_URL, carrier.getSimpleName())
        .endsWith(".invalid");
  }

  private static Map<String, Object> inlinedProperties(Class<?> carrier) {
    return TestPropertySourceUtils.convertInlinedPropertiesToMap(
        BootstrapUtils.resolveTestContextBootstrapper(carrier)
            .buildMergedContextConfiguration()
            .getPropertySourceProperties());
  }

  private static String hostOf(Map<String, Object> inlined, String key) {
    return URI.create(String.valueOf(inlined.get(key))).getHost();
  }
}
