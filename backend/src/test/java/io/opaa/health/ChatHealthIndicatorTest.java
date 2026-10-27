package io.opaa.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.llm.ActiveChatModelDescription;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.llm.NoActiveChatModelException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class ChatHealthIndicatorTest {

  @Test
  void reportsUpWithBaseUrlAndModelIdentifierWhenAModelIsActive() {
    ActiveChatModelResolver resolver = mock(ActiveChatModelResolver.class);
    when(resolver.resolveDescription())
        .thenReturn(new ActiveChatModelDescription("http://ollama:11434/v1", "phi3:mini"));
    ChatHealthIndicator indicator = new ChatHealthIndicator(resolver);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("baseUrl", "http://ollama:11434/v1")
        .containsEntry("modelIdentifier", "phi3:mini");
  }

  @Test
  void reportsDownWhenTheActiveModelCannotBeRead() {
    ActiveChatModelResolver resolver = mock(ActiveChatModelResolver.class);
    when(resolver.resolveDescription()).thenThrow(new IllegalStateException("database gone"));
    ChatHealthIndicator indicator = new ChatHealthIndicator(resolver);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void reportsUnknownAndNotConfiguredWithoutAnActiveModel() {
    ActiveChatModelResolver resolver = mock(ActiveChatModelResolver.class);
    when(resolver.resolveDescription()).thenThrow(mock(NoActiveChatModelException.class));
    ChatHealthIndicator indicator = new ChatHealthIndicator(resolver);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    assertThat(health.getDetails()).containsEntry("chatModel", "NOT_CONFIGURED");
  }
}
