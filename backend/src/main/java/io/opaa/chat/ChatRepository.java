package io.opaa.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChatRepository extends JpaRepository<Chat, UUID> {

  /**
   * The one lookup every chat-scoped operation (get/update/delete, and the persisted-chat path of
   * {@code QueryService#query}) goes through - a chat visible to no one but its author (#525), so
   * "exists but belongs to someone else" and "does not exist" collapse into the same empty result
   * on purpose.
   */
  Optional<Chat> findByIdAndAuthorId(UUID id, UUID authorId);

  List<Chat> findBySpaceIdAndAuthorIdOrderByUpdatedAtDesc(UUID spaceId, UUID authorId);

  /**
   * Used by {@code SpaceService#deleteSpace} to reject the delete with a clear 409 before it ever
   * reaches {@code fk_chats_space} (ON DELETE RESTRICT, migration 032) - see
   * docs/features/spaces-and-assets.md#chats-sind-vor-fremder-löschung-geschützt (#525 review,
   * finding 5).
   */
  boolean existsBySpaceId(UUID spaceId);

  /**
   * #561 review, finding 2: an atomic, DB-decided {@code UPDATE} instead of the load-mutate-{@code
   * save()} cycle {@code ChatService#appendTurn} used before - that {@link Chat} instance was
   * loaded by {@code QueryService#query} before retrieval and LLM answer generation (which can take
   * seconds), so a full merge {@code save()} of it would write back a stale snapshot of every other
   * column too, clobbering a concurrent {@code PATCH} rename that landed in between. Mirrors the
   * condition the removed {@code Chat#deriveTitleFromFirstQuestionIfAbsent} used to apply in
   * memory: only the true "never set" case ({@code title IS NULL}) falls back - a title explicitly
   * set to blank by the author is left alone.
   *
   * @return the number of rows updated (0 or 1) - 0 means the chat already had a title (or no
   *     longer exists)
   */
  @Transactional
  @Modifying
  @Query("UPDATE Chat c SET c.title = :title WHERE c.id = :chatId AND c.title IS NULL")
  int deriveTitleFromFirstQuestionIfAbsent(
      @Param("chatId") UUID chatId, @Param("title") String title);

  /**
   * Same reasoning as {@link #deriveTitleFromFirstQuestionIfAbsent} for {@code chats.updated_at}
   * (#525 review, finding/nit d - the chat list's "sorted by last use" ordering needs this bumped
   * on every turn, even one that changes no other column) - an atomic, targeted {@code UPDATE}
   * rather than a field set on a possibly-stale in-memory {@link Chat} later merged back in full.
   */
  @Transactional
  @Modifying
  @Query("UPDATE Chat c SET c.updatedAt = :updatedAt WHERE c.id = :chatId")
  void touch(@Param("chatId") UUID chatId, @Param("updatedAt") Instant updatedAt);

  /**
   * #557/#561: applies an LLM-derived title only if the chat's title is still {@code GENERATED} at
   * the moment this {@code UPDATE} actually executes - atomic and decided by the database itself,
   * so a {@code CUSTOM} title (set at any point, including during the asynchronous LLM call this
   * backs) can never be overwritten. The load-check-{@code save()} cycle this replaces was racy in
   * two ways the review found: self-invocation ({@code
   * ChatTitleGenerationService#applyGeneratedTitle} calling another method on {@code this} bypassed
   * the {@code @Transactional} proxy entirely, so {@code findById} and {@code save} ran as two
   * separate, unrelated transactions) and a stale merge (same class of bug as {@link
   * #deriveTitleFromFirstQuestionIfAbsent} above).
   *
   * <p>{@code @Transactional} lives here, directly on the repository method, rather than on
   * whichever service calls it (contrast {@code AuditActorPseudonymRepository#insertIfAbsent},
   * whose caller supplies the transaction): {@link ChatTitleGenerationService#generateTitleAsync}
   * calls this method directly on the injected {@code ChatRepository} bean, never through a
   * self-invoked call on {@code this} - so there is no self-invocation hazard to guard against by
   * keeping this annotation elsewhere, and putting it here means this method is atomic and
   * transactional no matter which caller (with or without an ambient transaction of its own)
   * eventually invokes it.
   *
   * @return the number of rows updated (0 or 1) - 0 means the title was {@code CUSTOM} or the chat
   *     no longer exists.
   */
  @Transactional
  @Modifying
  @Query(
      "UPDATE Chat c SET c.title = :title WHERE c.id = :chatId AND c.titleSource ="
          + " io.opaa.chat.TitleSource.GENERATED")
  int applyGeneratedTitleIfGenerated(@Param("chatId") UUID chatId, @Param("title") String title);
}
