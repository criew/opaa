package io.opaa.audit;

import java.util.Map;
import java.util.Objects;

/**
 * How a secret of a setting changed, the only form in which a secret reaches the audit log
 * (ADR-0060, Entscheidung 11): a writer compares what it stored before and after - ciphertext or
 * value, never written out - and puts the result under {@code <secret>Change} into {@code after}.
 * An unchanged secret puts nothing there.
 */
public enum AuditSecretChange {
  /** There was no secret, now there is one. */
  SET,
  /** A new secret replaced the stored one; entering the same value again may count as one. */
  CHANGED,
  /** The stored secret was removed. */
  REMOVED;

  /** The change between two stored forms of a secret, {@code null} when they are equal. */
  public static AuditSecretChange between(String before, String after) {
    if (Objects.equals(before, after)) {
      return null;
    }
    if (before == null) {
      return SET;
    }
    return after == null ? REMOVED : CHANGED;
  }

  /**
   * Puts the change of {@code secret} under {@code <secret>Change} into {@code payload} when there
   * is one; {@code payload} must be mutable.
   */
  public static void put(Map<String, Object> payload, String secret, String before, String after) {
    AuditSecretChange change = between(before, after);
    if (change != null) {
      payload.put(secret + "Change", change.name());
    }
  }
}
