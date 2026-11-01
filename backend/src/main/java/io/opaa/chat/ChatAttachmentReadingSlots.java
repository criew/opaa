package io.opaa.chat;

import io.opaa.common.TooManyRequestsException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * The attachments one account has being read on this instance, at most {@code
 * opaa.chat.attachments.max-processing-per-account}: a person who drops a hundred files at once
 * slows only themselves. Per instance, like the reading pool it guards (ADR-0053).
 */
@Component
class ChatAttachmentReadingSlots {

  static final String BUSY_MESSAGE =
      "Es werden gerade zu viele Ihrer Anhänge gelesen - bitte in wenigen Sekunden erneut"
          + " versuchen.";

  static final long RETRY_AFTER_SECONDS = 5;

  private final int maxPerAccount;

  /** The slots each account holds; guarded by itself. */
  private final Map<UUID, Integer> held = new HashMap<>();

  ChatAttachmentReadingSlots(ChatAttachmentProperties properties) {
    this.maxPerAccount = properties.maxProcessingPerAccount();
  }

  /**
   * Takes one slot for {@code account}.
   *
   * @throws TooManyRequestsException when the account holds its maximum
   */
  Slot reserve(UUID account) {
    synchronized (held) {
      if (held.getOrDefault(account, 0) >= maxPerAccount) {
        throw new TooManyRequestsException(BUSY_MESSAGE, RETRY_AFTER_SECONDS);
      }
      held.merge(account, 1, Integer::sum);
    }
    return new Slot(account);
  }

  /** One taken slot; {@link #release()} gives it back once, however often it is called. */
  final class Slot {

    private final UUID account;
    private final AtomicBoolean released = new AtomicBoolean();

    private Slot(UUID account) {
      this.account = account;
    }

    void release() {
      if (released.compareAndSet(false, true)) {
        synchronized (held) {
          held.computeIfPresent(account, (key, count) -> count == 1 ? null : count - 1);
        }
      }
    }
  }
}
