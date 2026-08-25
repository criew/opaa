package io.opaa.query;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * A {@link ChatMemoryRepository} backed by a Caffeine cache with LRU eviction and TTL. Limits the
 * number of concurrent conversations to prevent unbounded memory growth.
 *
 * <p>{@code @Service} (#889, O2): previously wired manually in {@code QueryConfiguration}, along
 * with the {@code opaa.conversations.active} {@link Gauge} that {@link #size()} now backs -
 * self-registered in the constructor below like {@code ChatHealthIndicator} and the other
 * {@code @Component} classes in {@code io.opaa.observability} already do, rather than a separate
 * {@code @Bean} method. The two-/three-arg constructors below stay package-visible for tests that
 * need non-default limits or a synchronous eviction executor; {@link #CaffeineChatMemoryRepository(
 * MeterRegistry)} is the one Spring actually calls, marked {@link Autowired} because more than one
 * public constructor exists.
 */
@Service
public class CaffeineChatMemoryRepository implements ChatMemoryRepository {

  private static final Logger log = LoggerFactory.getLogger(CaffeineChatMemoryRepository.class);

  /**
   * Maximum number of concurrent conversation caches. Default 50: moderate memory usage suitable
   * for typical team sizes - each conversation holds up to {@link
   * QueryConfiguration#MAX_MESSAGES_PER_CONVERSATION} messages in a Caffeine cache entry.
   */
  static final int MAX_CONVERSATIONS = 50;

  /**
   * Time-to-live in minutes for idle conversations. Default 60: one hour covers a typical user
   * session; conversations are evicted after this period of inactivity to free memory.
   */
  static final int TTL_MINUTES = 60;

  private final Cache<String, List<Message>> cache;

  /** The constructor Spring calls - see this class's Javadoc. */
  @Autowired
  public CaffeineChatMemoryRepository(MeterRegistry meterRegistry) {
    this(MAX_CONVERSATIONS, TTL_MINUTES, null);
    Gauge.builder("opaa.conversations.active", this, CaffeineChatMemoryRepository::size)
        .description("Active conversations in memory")
        .register(meterRegistry);
  }

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

  /**
   * Returns every cache key, unfiltered by account. {@link ChatMemoryRepository} requires this
   * method with no per-user variant, but the keys this repository actually stores are {@code
   * currentUserId + ":" + effectiveChatId} (see {@code QueryService#query}) - so a caller that
   * treats the result as a listing of conversations for "the current user" would in fact see every
   * account's keys mixed together. #123 found no caller of this method (production or test) beyond
   * the interface contract itself; it must never be used to build a user-facing conversation
   * listing without first filtering by the caller's own user-id prefix.
   */
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

  /** Returns the number of active conversations in the cache. */
  long size() {
    return cache.estimatedSize();
  }

  /** Forces pending evictions to run synchronously. Intended for testing. */
  void cleanUp() {
    cache.cleanUp();
  }
}
