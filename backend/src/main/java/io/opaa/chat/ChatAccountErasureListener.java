package io.opaa.chat;

import io.opaa.account.AccountErasureEvent;
import io.opaa.common.ConflictException;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes the chats of an erased account with their messages, notes and attachments, its personal
 * marks and its held or requested chat deletion, in the erasure's transaction. A running hold of
 * the chat deletion refuses the erasure: whoever placed it needs the chats kept. The originals of
 * the attachments go with {@link ChatAttachmentCleanup}, which removes those whose row is gone.
 */
@Component
class ChatAccountErasureListener {

  static final String CHAT_CLEANUP_HELD = "CHAT_CLEANUP_HELD";

  private final LockedAccountChatCleanupStore store;
  private final JdbcTemplate jdbc;

  ChatAccountErasureListener(LockedAccountChatCleanupStore store, JdbcTemplate jdbc) {
    this.store = store;
    this.jdbc = jdbc;
  }

  @Order(AccountErasureEvent.CHATS_ORDER)
  @EventListener
  void onAccountErasure(AccountErasureEvent event) {
    UUID userId = event.user().getId();
    if (store.holdRunsAt(userId, event.at())) {
      throw new ConflictException(
          "Die Löschung der Chats dieses Kontos ist angehalten. Der Personenbezug bleibt, bis die"
              + " Anhaltung endet.",
          CHAT_CLEANUP_HELD);
    }
    jdbc.update("DELETE FROM chat_cleanup_hold_requests WHERE user_id = ?", userId);
    jdbc.update("DELETE FROM chat_cleanup_holds WHERE user_id = ?", userId);
    jdbc.update("DELETE FROM chat_personal_marks WHERE user_id = ?", userId);
    jdbc.update("DELETE FROM chat_attachments WHERE author_id = ?", userId);
    jdbc.update("DELETE FROM chats WHERE author_id = ?", userId);
  }
}
