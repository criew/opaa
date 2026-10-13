package io.opaa.indexing.chunk;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Whether the vector index matches the configured embedding model, held in the process (ADR-0021).
 * While it does not ({@link #isStale()}), the vector search is suspended and every chunk write puts
 * its vector into {@link #TARGET_COLUMN} instead of {@link #EMBEDDING_COLUMN}.
 *
 * <p>Writers run under {@link #whileWriting}, the switch from target to embedding column under
 * {@link #exclusively}: a writer never names a column the switch has just removed.
 */
@Component
public class EmbeddingIndexState {

  public static final String EMBEDDING_COLUMN = "embedding";
  public static final String TARGET_COLUMN = "embedding_next";

  /**
   * The index as it stands. {@code target} is the identity the target column is filled with, set
   * exactly while the index is stale.
   */
  public record Snapshot(
      EmbeddingIdentity current, EmbeddingIdentity configured, EmbeddingIdentity target) {

    public boolean stale() {
      return target != null;
    }
  }

  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private volatile Snapshot snapshot;

  /** {@code null} until the startup reconciliation ran. */
  public Snapshot snapshot() {
    return snapshot;
  }

  public boolean isStale() {
    Snapshot current = snapshot;
    return current != null && current.stale();
  }

  /** Whether the vector search may run: only on an index of the configured model. */
  public boolean isVectorSearchAvailable() {
    return !isStale();
  }

  /** Called by the reconciliation and by the switch, inside {@link #exclusively}. */
  public void replace(Snapshot replacement) {
    this.snapshot = replacement;
  }

  /**
   * Runs {@code write} with the column every new vector currently goes to. Concurrent writers do
   * not exclude each other.
   */
  <T> T whileWriting(Function<String, T> write) {
    return withStableColumns(current -> write.apply(isStale() ? TARGET_COLUMN : EMBEDDING_COLUMN));
  }

  /**
   * Runs {@code read} with the current snapshot while the columns cannot be switched, so what it
   * reads matches the snapshot. Writers are not held up.
   */
  public <T> T withStableColumns(Function<Snapshot, T> read) {
    lock.readLock().lock();
    try {
      return read.apply(snapshot);
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Runs {@code change} while no writer is between choosing a column and writing to it. */
  public <T> T exclusively(Supplier<T> change) {
    lock.writeLock().lock();
    try {
      return change.get();
    } finally {
      lock.writeLock().unlock();
    }
  }
}
