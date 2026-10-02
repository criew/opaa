package io.opaa.chat;

import io.opaa.space.ChatAutoCleanupProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The automatic chat cleanup of the spaces that switched it on (docs/features/chat-list.md,
 * "Automatisches Archivieren und Löschen je Space"): archives chats without activity and deletes
 * chats that stayed archived, each after its installation-wide period, never counted from before
 * switching on. Pinned chats take part in neither. Activity is {@code chats.updated_at} and nothing
 * else; the log names only totals, never a chat, space or person.
 */
@Service
public class ChatAutoCleanupService {

  private static final Logger log = LoggerFactory.getLogger(ChatAutoCleanupService.class);

  static final int DELETE_BATCH_SIZE = 200;

  private final ChatRepository chatRepository;
  private final ChatPersonalMarkRepository markRepository;
  private final ChatAutoCleanupProperties properties;
  private final TransactionTemplate transactionTemplate;

  public ChatAutoCleanupService(
      ChatRepository chatRepository,
      ChatPersonalMarkRepository markRepository,
      ChatAutoCleanupProperties properties,
      PlatformTransactionManager transactionManager) {
    this.chatRepository = chatRepository;
    this.markRepository = markRepository;
    this.properties = properties;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * One run at {@code now}: deletes first, then archives, so a chat archived in this run is never
   * deleted in the same one. Each delete batch commits on its own; the caller holds no transaction.
   */
  public void runOnce(Instant now) {
    int deleted = deleteDue(now);
    Integer archived =
        transactionTemplate.execute(
            status ->
                markRepository.archiveInactiveForAutoCleanup(properties.archiveCutoff(now), now));
    if (deleted > 0 || (archived != null && archived > 0)) {
      log.info("Chat auto cleanup: archived {} chat(s), deleted {} chat(s)", archived, deleted);
    }
  }

  private int deleteDue(Instant now) {
    Instant cutoff = properties.deleteCutoff(now);
    List<UUID> due = chatRepository.findDueForAutoCleanupDeletion(cutoff);
    int deleted = 0;
    for (int from = 0; from < due.size(); from += DELETE_BATCH_SIZE) {
      deleted +=
          deleteBatch(due.subList(from, Math.min(from + DELETE_BATCH_SIZE, due.size())), cutoff);
    }
    return deleted;
  }

  /**
   * Deletes the chats among {@code candidates} that are still due at {@code cutoff}, in one
   * transaction; returns how many. Each candidate is checked again under a lock, so one rescued or
   * switched off since it was found is kept.
   */
  int deleteBatch(List<UUID> candidates, Instant cutoff) {
    Integer count =
        transactionTemplate.execute(
            status -> {
              List<Chat> chats =
                  chatRepository.findAllById(
                      chatRepository.lockStillDueForAutoCleanupDeletion(candidates, cutoff));
              chatRepository.deleteAll(chats);
              return chats.size();
            });
    return count == null ? 0 : count;
  }
}
