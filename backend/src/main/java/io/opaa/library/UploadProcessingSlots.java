package io.opaa.library;

import io.opaa.common.TooManyRequestsException;
import io.opaa.knowledge.UploadProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * The places of this instance's upload pool ({@code opaa.upload.thread-pool}: threads plus queue),
 * taken by an upload before anything of it is stored and given back when its processing ends. A
 * full pool is answered with {@code 429} and {@code Retry-After} while nothing is written, instead
 * of a document that fails at once. One account holds at most {@code
 * opaa.upload.max-places-per-account} places, so the others still find one. Per instance, like the
 * pool it mirrors (ADR-0053).
 */
@Component
public class UploadProcessingSlots {

  /** The answer to an upload that finds the pool full. */
  static final String BUSY_MESSAGE =
      "Die Verarbeitung von Uploads ist gerade ausgelastet - bitte in wenigen Sekunden erneut"
          + " versuchen.";

  /** The answer to an upload of an account that holds all the places it may. */
  static final String ACCOUNT_BUSY_MESSAGE =
      "Für dieses Konto sind gerade zu viele Uploads in Verarbeitung - bitte in wenigen Sekunden"
          + " erneut versuchen.";

  /** The seconds a refused upload is asked to wait. */
  static final long RETRY_AFTER_SECONDS = 5;

  private final Semaphore places;
  private final int maxPerAccount;

  /** The places each account holds; guarded by itself. */
  private final Map<UUID, Integer> held = new HashMap<>();

  public UploadProcessingSlots(UploadProperties properties) {
    this.places = new Semaphore(properties.threadPool().places());
    this.maxPerAccount = properties.maxPlacesPerAccount();
  }

  /**
   * Takes one place for {@code account}.
   *
   * @throws TooManyRequestsException when every place is taken or the account holds its maximum
   */
  public Slot reserve(UUID account) {
    synchronized (held) {
      if (held.getOrDefault(account, 0) >= maxPerAccount) {
        throw new TooManyRequestsException(ACCOUNT_BUSY_MESSAGE, RETRY_AFTER_SECONDS);
      }
      if (!places.tryAcquire()) {
        throw busy();
      }
      held.merge(account, 1, Integer::sum);
    }
    return new Slot(account);
  }

  /** The refusal of an upload the pool has no place for. */
  static TooManyRequestsException busy() {
    return new TooManyRequestsException(BUSY_MESSAGE, RETRY_AFTER_SECONDS);
  }

  int available() {
    return places.availablePermits();
  }

  /** One taken place; {@link #release()} gives it back once, however often it is called. */
  public final class Slot {

    private final UUID account;
    private final AtomicBoolean released = new AtomicBoolean();

    private Slot(UUID account) {
      this.account = account;
    }

    public void release() {
      if (released.compareAndSet(false, true)) {
        synchronized (held) {
          held.computeIfPresent(account, (key, count) -> count == 1 ? null : count - 1);
        }
        places.release();
      }
    }
  }
}
