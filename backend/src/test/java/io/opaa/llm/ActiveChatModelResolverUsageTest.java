package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opaa.instance.InvalidationBus;
import io.opaa.observability.ModelCallPurpose;
import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * {@link ActiveChatModelResolver#recordUsage} counts a call's tokens under the identifier the model
 * administration configured, never under the model name the provider reports in its response.
 */
class ActiveChatModelResolverUsageTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Test
  void theTokensOfACallCountUnderPurposeTokenTypeAndTheConfiguredModel() {
    ActiveChatModelResolver resolver = resolver();
    resolver.resolveChatClient();

    resolver.recordUsage(ModelCallPurpose.TITLE, response("provider-model-2026-01-01", 120, 30));

    assertThat(tokens("title", "input", "model-r")).isEqualTo(120);
    assertThat(tokens("title", "output", "model-r")).isEqualTo(30);
    assertThat(meterRegistry.find("opaa.model.tokens").tag("model", "provider-model-2026-01-01"))
        .satisfies(search -> assertThat(search.counters()).isEmpty());
  }

  @Test
  void aResponseWithoutUsageCountsNothing() {
    ActiveChatModelResolver resolver = resolver();
    resolver.resolveChatClient();

    resolver.recordUsage(
        ModelCallPurpose.NOTE,
        new ChatResponse(List.of(new Generation(new AssistantMessage("x")))));
    resolver.recordUsage(ModelCallPurpose.NOTE, null);

    assertThat(meterRegistry.find("opaa.model.tokens").counters()).isEmpty();
  }

  private double tokens(String purpose, String tokenType, String model) {
    return meterRegistry
        .get("opaa.model.tokens")
        .tags("purpose", purpose, "token_type", tokenType, "model", model)
        .counter()
        .count();
  }

  private static ChatResponse response(String reportedModel, int input, int output) {
    return new ChatResponse(
        List.of(new Generation(new AssistantMessage("Antwort"))),
        ChatResponseMetadata.builder()
            .model(reportedModel)
            .usage(new DefaultUsage(input, output))
            .build());
  }

  private ActiveChatModelResolver resolver() {
    LlmModelRepository repository = mock(LlmModelRepository.class);
    when(repository.findAllByActiveTrue())
        .thenReturn(
            List.of(
                new LlmModel(
                    "Modell R", "http://127.0.0.1:9/v1", "model-r", BigDecimal.ZERO, 256, null)));
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("meterRegistry", meterRegistry);
    return new ActiveChatModelResolver(
        repository,
        mock(SettingsEncryptor.class),
        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class),
        beans.getBeanProvider(MeterRegistry.class),
        mock(InvalidationBus.class));
  }
}
