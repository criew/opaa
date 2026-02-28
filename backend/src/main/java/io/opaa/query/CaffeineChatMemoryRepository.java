package io.opaa.query;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

/**
 * A {@link ChatMemoryRepository} backed by a Caffeine cache with LRU eviction and TTL. Limits the
 * number of concurrent conversations to prevent unbounded memory growth.
 */
public class CaffeineChatMemoryRepository implements ChatMemoryRepository {

  private static final Logger log = LoggerFactory.getLogger(CaffeineChatMemoryRepository.class);

  private final Cache<String, List<Message>> cache;

  /**
   * Creates a new repository with the given limits.
   *
   * @param maxConversations maximum number of conversations to keep (LRU eviction)
   * @param ttlMinutes time-to-live in minutes after last access before a conversation is evicted
   */
  public CaffeineChatMemoryRepository(int maxConversations, int ttlMinutes) {
    this(maxConversations, ttlMinutes, null);
  }

  CaffeineChatMemoryRepository(int maxConversations, int ttlMinutes, Executor executor) {
    Caffeine<Object, Object> builder =
        Caffeine.newBuilder()
            .maximumSize(maxConversations)
            .expireAfterAccess(ttlMinutes, TimeUnit.MINUTES)
            .evictionListener(
                (key, value, cause) ->
                    log.debug("Evicted conversation '{}' due to {}", key, cause));

    if (executor != null) {
      builder.executor(executor);
    }

    this.cache = builder.build();

    log.info(
        "ChatMemory repository initialized: maxConversations={}, ttlMinutes={}",
        maxConversations,
        ttlMinutes);
  }

  @Override
  public List<String> findConversationIds() {
    return List.copyOf(cache.asMap().keySet());
  }

  @Override
  public List<Message> findByConversationId(String conversationId) {
    List<Message> messages = cache.getIfPresent(conversationId);
    return messages != null ? List.copyOf(messages) : List.of();
  }

  @Override
  public void saveAll(String conversationId, List<Message> messages) {
    cache.put(conversationId, new ArrayList<>(messages));
  }

  @Override
  public void deleteByConversationId(String conversationId) {
    cache.invalidate(conversationId);
  }

  /** Forces pending evictions to run synchronously. Intended for testing. */
  void cleanUp() {
    cache.cleanUp();
  }
}
