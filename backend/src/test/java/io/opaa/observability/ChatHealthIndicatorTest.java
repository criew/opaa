package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.llm.ActiveChatModelDescription;
import io.opaa.llm.ActiveChatModelResolver;
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
  void reportsDownWhenNoModelIsActive() {
    ActiveChatModelResolver resolver = mock(ActiveChatModelResolver.class);
    when(resolver.resolveDescription()).thenThrow(new NoActiveChatModelExceptionForTest());
    ChatHealthIndicator indicator = new ChatHealthIndicator(resolver);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  /**
   * {@code io.opaa.llm.NoActiveChatModelException} has a package-private constructor - {@link
   * ActiveChatModelResolver} is its only intended thrower - so this test-only stand-in exercises
   * "no active model" without needing package access to {@code io.opaa.llm}.
   */
  private static final class NoActiveChatModelExceptionForTest extends RuntimeException {}
}
