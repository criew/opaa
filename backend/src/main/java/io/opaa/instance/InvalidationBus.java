package io.opaa.instance;

/**
 * The invalidation bus between the instances of an installation (ADR-0053, Entscheidung 5): a
 * change of what a cache holds is announced on a Postgres channel, and every instance - the sending
 * one too - drops the caches bound to the announced topic.
 *
 * <p>A table-backed cache needs no {@link #publish}: a trigger of its table announces every change,
 * in the transaction that makes it and from whichever instance or version writes it. {@link
 * #publish} is for changes that live in no table of their own.
 */
public interface InvalidationBus {

  /**
   * Announces {@code topic} in the current transaction: delivered with its commit, never after a
   * rollback; without a transaction at once. The topic names a cache, never a person or a row.
   */
  void publish(String topic);

  /**
   * Whether this instance currently hears every announcement. While it does not, a bound cache must
   * not answer from memory but read past itself from the database.
   */
  boolean isListening();
}
