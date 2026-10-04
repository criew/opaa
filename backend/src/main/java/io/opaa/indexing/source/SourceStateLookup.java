package io.opaa.indexing.source;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a library's source is still being updated - a read-only question, separate from {@link
 * SourceConnectionResolver}, which hands out targets and secrets and stays with the core. The
 * answer path asks this port to show "Stand vom …" (spec "Konnektor-Freigabe und Sperre").
 */
public interface SourceStateLookup {

  /** The libraries among {@code libraries} whose source is not updated now; the others absent. */
  Map<UUID, SourceState> frozenAmong(Collection<KnowledgeLibrary> libraries);

  /**
   * Why a source is not updated, and who can change that.
   *
   * @param responsible the German name of who is in charge ("Systemverwaltung", "Verwaltende der
   *     Bibliothek")
   */
  record SourceState(Reason reason, String responsible) {}

  /** Why a source is not updated. */
  enum Reason {
    /** The system administration locked the connector type or the profile. */
    LOCKED,
    /** The connection holds no usable secret, e.g. after "Alle Verbindungen trennen". */
    NOT_CONNECTED,
    /** The library's profile was deleted ("Zugang entfernt"). */
    ACCESS_REMOVED
  }
}
