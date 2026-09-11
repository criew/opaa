package io.opaa.query.answer;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.query.QueryProperties;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The conversation window's width is the configured property and nothing else (#1486) - the fixed
 * point the multi-turn harness measures off this very bean.
 */
class ConversationMemoryConfigurationTest {

  @Test
  void theWindowKeepsExactlyTheConfiguredNumberOfMessages() {
    assertThat(windowOf(20)).isEqualTo(20);
    assertThat(windowOf(4)).isEqualTo(4);
  }

  private static int windowOf(int conversationWindowMessages) {
    ChatMemory chatMemory =
        new ConversationMemoryConfiguration()
            .chatMemory(
                new CaffeineChatMemoryRepository(new SimpleMeterRegistry()),
                new QueryProperties(
                    8, 25, 1.0, 0.3, true, 3, 2, true, 50, conversationWindowMessages, 2));
    String conversationId = "window-probe-" + UUID.randomUUID();
    List<Message> probe =
        IntStream.range(0, conversationWindowMessages + 10)
            .<Message>mapToObj(i -> new UserMessage("Nachricht " + i))
            .toList();

    chatMemory.add(conversationId, probe);

    return chatMemory.get(conversationId).size();
  }
}
