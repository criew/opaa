package io.opaa.indexing.source;

import java.util.Objects;

/**
 * Why a library's source is not reached now, who can change that, and the German notice a refused
 * run, a refused original and the library itself carry (spec "Konnektor-Freigabe und Sperre").
 *
 * @param responsible the German name of who is in charge ("Systemverwaltung", "Verwaltende der
 *     Bibliothek")
 */
public record SourceBlock(Reason reason, String responsible, String notice) {

  public SourceBlock {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(responsible, "responsible");
    Objects.requireNonNull(notice, "notice");
  }

  /** Why a source is not reached. */
  public enum Reason {
    /** The system administration locked the library's connector type. */
    TYPE_LOCKED,
    /** The system administration locked the library's connection profile. */
    PROFILE_LOCKED,
    /** The library's profile was deleted ("Zugang entfernt"). */
    ACCESS_REMOVED,
    /** The library's address does not lie under the server address of its profile. */
    TARGET_OUTSIDE_PROFILE,
    /** The profile asks for a secret the connection does not hold (yet or any more). */
    NOT_CONNECTED
  }

  /** Whether the system administration locked the source: it stays blocked until lifted. */
  public boolean locked() {
    return reason == Reason.TYPE_LOCKED || reason == Reason.PROFILE_LOCKED;
  }
}
