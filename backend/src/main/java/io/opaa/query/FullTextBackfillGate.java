package io.opaa.query;

import io.opaa.indexing.FullTextBackfillProgress;
import io.opaa.indexing.FullTextBackfillProgressService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Which libraries the lexical search path is allowed to search (docs/features/hybrid-retrieval.md,
 * "Reihenfolge": "Aufgenommen in die Fusion wird der Pfad erst, wenn der Backfill einer Bibliothek
 * abgeschlossen ist").
 *
 * <p>The rule exists because a half-filled full-text index is worse than none: it returns hits, and
 * nobody notices the ones it cannot return. The gate is therefore per library, not global - a
 * freshly added library still catching up must not silence the path for every library that is
 * already complete.
 *
 * <p><b>Cached, because the underlying count is expensive and the answer is monotone.</b> {@link
 * FullTextBackfillProgressService} counts against {@code vector_store} without an index on the
 * {@code library_id} metadata key; running that per query would put a table scan into the retrieval
 * path. A library that is complete stays complete: every chunk written after #1047 gets its {@code
 * chunk_full_text} row in the same transaction as its vector row (see {@code
 * VectorChunkStore#addChunks}), so completion is never lost while the process runs. A completed
 * library is consequently cached for the process's lifetime, an incomplete one re-checked at most
 * once per {@link #RECHECK_INTERVAL}.
 *
 * <p>The one event that <em>does</em> invalidate a completion - a raised {@code
 * FullTextChunkStore#CURRENT_TSV_VERSION}, which turns every existing row into a stale one - can
 * only arrive with a new deployment, and therefore with a fresh process and an empty cache. Same
 * reasoning as {@code FullTextBackfillScheduler}'s own dormancy flag; single instance per ADR-0021.
 */
@Component
class FullTextBackfillGate {

  /** How long an incomplete library keeps its answer before the counts are read again. */
  static final Duration RECHECK_INTERVAL = Duration.ofSeconds(60);

  private final FullTextBackfillProgressService progressService;
  private final Clock clock;
  private final Map<UUID, Instant> incompleteUntil = new ConcurrentHashMap<>();
  private final Set<UUID> complete = ConcurrentHashMap.newKeySet();

  @Autowired
  FullTextBackfillGate(FullTextBackfillProgressService progressService) {
    this(progressService, Clock.systemUTC());
  }

  /** Test seam: lets {@code FullTextBackfillGateTest} advance time instead of sleeping a minute. */
  FullTextBackfillGate(FullTextBackfillProgressService progressService, Clock clock) {
    this.progressService = progressService;
    this.clock = clock;
  }

  /**
   * The subset of {@code searchScope} whose full-text backfill has finished - never more than was
   * passed in. The gate narrows the search scope; it can never widen it, which is what keeps it
   * from becoming a second, weaker permission decision (ADR-0008 §5).
   */
  Set<UUID> searchableLibraries(Set<UUID> searchScope) {
    Set<UUID> searchable = new LinkedHashSet<>();
    Instant now = clock.instant();
    for (UUID libraryId : searchScope) {
      if (isComplete(libraryId, now)) {
        searchable.add(libraryId);
      }
    }
    return searchable;
  }

  private boolean isComplete(UUID libraryId, Instant now) {
    if (complete.contains(libraryId)) {
      return true;
    }
    Instant suppressedUntil = incompleteUntil.get(libraryId);
    if (suppressedUntil != null && now.isBefore(suppressedUntil)) {
      return false;
    }
    FullTextBackfillProgress progress = progressService.progressForLibrary(libraryId);
    if (progress.isComplete()) {
      complete.add(libraryId);
      incompleteUntil.remove(libraryId);
      return true;
    }
    incompleteUntil.put(libraryId, now.plus(RECHECK_INTERVAL));
    return false;
  }
}
