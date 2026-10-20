package io.opaa.query.answer;

import io.opaa.instance.InvalidationBus;
import io.opaa.library.PrivateLibraryErased;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Announces the erasure of a private library to the conversation memory of every instance
 * (ADR-0053, Entscheidung 5), inside the erasing transaction: delivered with its commit, never
 * after a rollback. The announcement carries the topic only, never the owner.
 */
@Component
class ChatMemoryErasureAnnouncement {

  private final InvalidationBus bus;

  ChatMemoryErasureAnnouncement(InvalidationBus bus) {
    this.bus = bus;
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
  void onPrivateLibraryErased(PrivateLibraryErased event) {
    bus.publish(CaffeineChatMemoryRepository.INVALIDATION_TOPIC);
  }
}
