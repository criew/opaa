package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

class CaffeineChatMemoryRepositoryTest {

  private CaffeineChatMemoryRepository repository;

  @BeforeEach
  void setUp() {
    repository = new CaffeineChatMemoryRepository(50, 60);
  }

  @Test
  void saveAndFindMessages() {
    List<Message> messages = List.of(new UserMessage("Hello"), new AssistantMessage("Hi there"));

    repository.saveAll("conv-1", messages);

    List<Message> result = repository.findByConversationId("conv-1");
    assertThat(result).hasSize(2);
    assertThat(result.get(0).getText()).isEqualTo("Hello");
    assertThat(result.get(1).getText()).isEqualTo("Hi there");
  }

  @Test
  void findByConversationIdReturnsEmptyListForUnknownId() {
    List<Message> result = repository.findByConversationId("nonexistent");
    assertThat(result).isEmpty();
  }

  @Test
  void findConversationIdsReturnsAllActiveIds() {
    repository.saveAll("conv-1", List.of(new UserMessage("Q1")));
    repository.saveAll("conv-2", List.of(new UserMessage("Q2")));
    repository.saveAll("conv-3", List.of(new UserMessage("Q3")));

    List<String> ids = repository.findConversationIds();
    assertThat(ids).containsExactlyInAnyOrder("conv-1", "conv-2", "conv-3");
  }

  @Test
  void findConversationIdsReturnsEmptyListWhenEmpty() {
    assertThat(repository.findConversationIds()).isEmpty();
  }

  @Test
  void deleteByConversationIdRemovesConversation() {
    repository.saveAll("conv-1", List.of(new UserMessage("Q1")));
    repository.saveAll("conv-2", List.of(new UserMessage("Q2")));

    repository.deleteByConversationId("conv-1");

    assertThat(repository.findByConversationId("conv-1")).isEmpty();
    assertThat(repository.findByConversationId("conv-2")).hasSize(1);
    assertThat(repository.findConversationIds()).containsExactly("conv-2");
  }

  @Test
  void deleteByConversationIdDoesNothingForUnknownId() {
    repository.saveAll("conv-1", List.of(new UserMessage("Q1")));

    repository.deleteByConversationId("nonexistent");

    assertThat(repository.findByConversationId("conv-1")).hasSize(1);
  }

  @Test
  void saveAllOverwritesExistingConversation() {
    repository.saveAll("conv-1", List.of(new UserMessage("Old question")));
    repository.saveAll(
        "conv-1", List.of(new UserMessage("New question"), new AssistantMessage("New answer")));

    List<Message> result = repository.findByConversationId("conv-1");
    assertThat(result).hasSize(2);
    assertThat(result.get(0).getText()).isEqualTo("New question");
  }

  @Test
  void lruEvictionRemovesOldestConversations() {
    // Use synchronous executor so eviction happens immediately
    var smallRepository = new CaffeineChatMemoryRepository(3, 60, Runnable::run);

    smallRepository.saveAll("conv-1", List.of(new UserMessage("Q1")));
    smallRepository.saveAll("conv-2", List.of(new UserMessage("Q2")));
    smallRepository.saveAll("conv-3", List.of(new UserMessage("Q3")));

    // Access conv-1 to make it recently used
    smallRepository.findByConversationId("conv-1");

    // Add a 4th conversation, which should trigger eviction of conv-2 (least recently used)
    smallRepository.saveAll("conv-4", List.of(new UserMessage("Q4")));
    smallRepository.cleanUp();

    List<String> ids = smallRepository.findConversationIds();
    assertThat(ids).hasSize(3);
    // conv-4 (newest) and conv-1 (recently accessed) should survive
    assertThat(ids).contains("conv-4", "conv-1");
  }

  @Test
  void findByConversationIdReturnsDefensiveCopy() {
    repository.saveAll("conv-1", List.of(new UserMessage("Q1")));

    List<Message> result = repository.findByConversationId("conv-1");

    // Returned list should be unmodifiable (defensive copy)
    assertThat(result).isUnmodifiable();
  }
}
