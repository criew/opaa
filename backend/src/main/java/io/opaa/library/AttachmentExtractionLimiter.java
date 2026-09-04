package io.opaa.library;

import io.opaa.common.ServiceUnavailableException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Bounds what the synchronous attachment re-extraction path can cost at once (#1243): re-extracting
 * an attachment parses - and, for a connector Bestand, downloads - its parent original, and every
 * VIEWER can trigger that repeatedly and in parallel.
 *
 * <p>Two guards, applied in this order:
 *
 * <ul>
 *   <li><b>Serialization per parent</b> - concurrent requests for attachments of the same parent
 *       document run one after the other, so a burst of clicks on one mail's attachments costs one
 *       parse at a time instead of one per request.
 *   <li><b>A global ceiling</b> ({@link AttachmentExtractionProperties#maxConcurrent()}) on how
 *       many re-extractions run at all, so the temporary disk and source load this path can occupy
 *       has an upper bound independent of the number of callers.
 * </ul>
 *
 * <p>Neither guard waits without limit: a request that has not got its turn within {@link
 * AttachmentExtractionProperties#acquireTimeout()} is answered with 503 and a German message rather
 * than holding a request thread. The parent lock is taken before the global permit, so requests
 * queued behind the same parent do not occupy permits while they wait.
 *
 * <p>Only the request path uses this. The background re-index ({@code PipelineReindexService}) runs
 * on its own bounded executor and is deliberately not limited here.
 */
@Component
public class AttachmentExtractionLimiter {

  private static final String BUSY_MESSAGE =
      "Es werden gerade zu viele Anhänge geöffnet. Bitte versuchen Sie es in einem Moment erneut.";

  /**
   * Only parents with a request in flight are present - an entry is created on the first arrival
   * and removed by the last one to leave, so this never grows beyond the number of requests
   * currently in this method. Guarded by its own monitor rather than a concurrent map, because
   * creation and the arrival count have to happen together.
   */
  private final Map<UUID, ParentLock> parentLocks = new HashMap<>();

  private final Semaphore permits;
  private final long timeoutMillis;

  public AttachmentExtractionLimiter(AttachmentExtractionProperties properties) {
    this.permits = new Semaphore(properties.maxConcurrent(), true);
    this.timeoutMillis = properties.acquireTimeout().toMillis();
  }

  /**
   * Runs {@code extraction} under both guards and returns its result. Throws {@link
   * ServiceUnavailableException} - never a 5xx of its own - when the request did not get its turn
   * in time; {@code extraction} has then not run at all.
   */
  public <T> T runExtraction(UUID parentDocumentId, Supplier<T> extraction) {
    ParentLock parentLock = arrive(parentDocumentId);
    try {
      if (!tryLock(parentLock.lock)) {
        throw new ServiceUnavailableException(BUSY_MESSAGE);
      }
      try {
        if (!tryAcquire()) {
          throw new ServiceUnavailableException(BUSY_MESSAGE);
        }
        try {
          return extraction.get();
        } finally {
          permits.release();
        }
      } finally {
        parentLock.lock.unlock();
      }
    } finally {
      leave(parentDocumentId, parentLock);
    }
  }

  private ParentLock arrive(UUID parentDocumentId) {
    synchronized (parentLocks) {
      ParentLock parentLock = parentLocks.computeIfAbsent(parentDocumentId, id -> new ParentLock());
      parentLock.arrivals++;
      return parentLock;
    }
  }

  private void leave(UUID parentDocumentId, ParentLock parentLock) {
    synchronized (parentLocks) {
      if (--parentLock.arrivals == 0) {
        parentLocks.remove(parentDocumentId, parentLock);
      }
    }
  }

  private boolean tryLock(ReentrantLock lock) {
    try {
      return lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException(BUSY_MESSAGE, e);
    }
  }

  private boolean tryAcquire() {
    try {
      return permits.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException(BUSY_MESSAGE, e);
    }
  }

  /** One parent document's lock plus how many requests currently hold a reference to it. */
  private static final class ParentLock {
    private final ReentrantLock lock = new ReentrantLock(true);
    private int arrivals;
  }
}
