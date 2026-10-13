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
 * No context of the suite may reach a real chat model over the network. On first start {@code
 * LlmModelSeeder} turns {@code spring.ai.openai.chat.base-url} into the active chat model of the
 * test database; left at the {@code application.yml} default, that is a locally running Ollama,
 * which retrieval (sub-question decomposition) then actually calls.
 *
 * <p>Inline test properties outrank {@code application.yml} and OS environment variables, so an
 * entry in a signature's own properties decides the seeded address regardless of the developer's
 * shell. A host under {@code .invalid} (RFC 6761) never resolves, so the call fails at once, as it
 * does in CI. Built from {@code MergedContextConfiguration} alone: no context, no Docker.
 */
class TestChatModelEndpointTest {

  private static final String CHAT_BASE_URL = "spring.ai.openai.chat.base-url";

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
  void theSeededChatModelPointsAtAnAddressThatNeverAnswers(Class<?> carrier) {
    Map<String, Object> inlined =
        TestPropertySourceUtils.convertInlinedPropertiesToMap(
            BootstrapUtils.resolveTestContextBootstrapper(carrier)
                .buildMergedContextConfiguration()
                .getPropertySourceProperties());

    assertThat(inlined)
        .as(
            "%s must set %s itself, otherwise the seeded chat model is the application.yml"
                + " default and a locally running model server receives real chat requests",
            carrier.getSimpleName(), CHAT_BASE_URL)
        .containsKey(CHAT_BASE_URL);
    assertThat(URI.create(String.valueOf(inlined.get(CHAT_BASE_URL))).getHost())
        .as("host of %s in %s", CHAT_BASE_URL, carrier.getSimpleName())
        .endsWith(".invalid");
  }
}
