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

  /**
   * The libraries among {@code libraries} whose source is not updated now, each with its block; the
   * others absent. Reports a lock, a removed profile and a missing secret, but no address outside
   * the profile.
   */
  Map<UUID, SourceBlock> frozenAmong(Collection<KnowledgeLibrary> libraries);
}
