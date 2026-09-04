package io.opaa.query;

import io.opaa.indexing.FullTextIndexFillState;
import io.opaa.indexing.FullTextIndexFillStateService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * How many libraries of a search scope hold chunks the lexical path cannot find - a chunk without a
 * {@code chunk_full_text} row at the current {@code FullTextChunkStore#CURRENT_TSV_VERSION}
 * (docs/features/hybrid-retrieval.md, "Arbeitspaket 2a").
 *
 * <p><b>Reports, never narrows (#1270).</b> The predecessor of this class kept an incomplete
 * library out of the lexical path entirely; that gate is gone, so such a library <em>is</em>
 * searched and contributes a partially filled list. This class exists so that state does not stay
 * silent in the explanation protocol: {@link FullTextSearchStage} records the number in its notes,
 * and the administration page shows the same condition per library.
 *
 * <p><b>Cached, because the underlying count is not free and the answer is monotone.</b> {@link
 * FullTextIndexFillStateService} counts against {@code vector_store} via the expression index on
 * the {@code library_id} metadata key (#1119); caching still avoids running that count on every
 * query. A library that is complete stays complete while the process runs: every chunk written
 * after #1047 gets its {@code chunk_full_text} row in the same transaction as its vector row. The
 * one event that invalidates a completion - a raised {@code CURRENT_TSV_VERSION} - can only arrive
 * with a new deployment, and therefore with a fresh process and an empty cache. An incomplete
 * library is re-checked at most once per {@link #RECHECK_INTERVAL}, so a finished re-index becomes
 * visible without a restart.
 */
@Component
class FullTextIndexCompleteness {

  /** How long an incomplete library keeps its answer before the counts are read again. */
  static final Duration RECHECK_INTERVAL = Duration.ofSeconds(60);

  private final FullTextIndexFillStateService fillStateService;
  private final Clock clock;
  private final Map<UUID, Instant> incompleteUntil = new ConcurrentHashMap<>();
  private final Set<UUID> complete = ConcurrentHashMap.newKeySet();

  @Autowired
  FullTextIndexCompleteness(FullTextIndexFillStateService fillStateService) {
    this(fillStateService, Clock.systemUTC());
  }

  /** Test seam: lets the test advance time instead of sleeping a minute. */
  FullTextIndexCompleteness(FullTextIndexFillStateService fillStateService, Clock clock) {
    this.fillStateService = fillStateService;
    this.clock = clock;
  }

  /** How many libraries of {@code searchScope} are missing chunks from the full-text index. */
  long incompleteLibraryCount(Set<UUID> searchScope) {
    Instant now = clock.instant();
    long incomplete = 0;
    for (UUID libraryId : searchScope) {
      if (!isComplete(libraryId, now)) {
        incomplete++;
      }
    }
    return incomplete;
  }

  private boolean isComplete(UUID libraryId, Instant now) {
    if (complete.contains(libraryId)) {
      return true;
    }
    Instant suppressedUntil = incompleteUntil.get(libraryId);
    if (suppressedUntil != null && now.isBefore(suppressedUntil)) {
      return false;
    }
    FullTextIndexFillState fillState = fillStateService.fillStateForLibrary(libraryId);
    if (fillState.isComplete()) {
      complete.add(libraryId);
      incompleteUntil.remove(libraryId);
      return true;
    }
    incompleteUntil.put(libraryId, now.plus(RECHECK_INTERVAL));
    return false;
  }
}
