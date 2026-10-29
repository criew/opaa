package io.opaa.library;

import io.opaa.common.TooManyRequestsException;
import io.opaa.knowledge.UploadProperties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * The places of this instance's upload pool ({@code opaa.upload.thread-pool}: threads plus queue),
 * taken by an upload before anything of it is stored and given back when its processing ends. A
 * full pool is answered with {@code 429} and {@code Retry-After} while nothing is written, instead
 * of a document that fails at once. Per instance, like the pool it mirrors (ADR-0053).
 */
@Component
public class UploadProcessingSlots {

  /** The answer to an upload that finds the pool full. */
  static final String BUSY_MESSAGE =
      "Die Verarbeitung von Uploads ist gerade ausgelastet - bitte in wenigen Sekunden erneut"
          + " versuchen.";

  /** The seconds a refused upload is asked to wait. */
  static final long RETRY_AFTER_SECONDS = 5;

  private final Semaphore places;

  public UploadProcessingSlots(UploadProperties properties) {
    this.places =
        new Semaphore(properties.threadPool().maxSize() + properties.threadPool().queueCapacity());
  }

  /**
   * Takes one place.
   *
   * @throws TooManyRequestsException when every place is taken
   */
  public Slot reserve() {
    if (!places.tryAcquire()) {
      throw busy();
    }
    return new Slot();
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

    private final AtomicBoolean released = new AtomicBoolean();

    private Slot() {}

    public void release() {
      if (released.compareAndSet(false, true)) {
        places.release();
      }
    }
  }
}
