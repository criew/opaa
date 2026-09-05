package io.opaa.indexing;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The chargen loop the resumable document runs share (#1305): pipeline re-index ({@link
 * PipelineReindexService}), the deterministic Bestandslauf ({@code MetadataBackfillService}) and
 * the value remapping of a library field ({@code LibraryMetadataFieldService}). Selection and
 * processing unit are the caller's; the loop owns only the mechanics all three need.
 *
 * <p><b>Every call terminates and every call makes progress.</b> A candidate the unit cannot
 * advance right now stays in the candidate set on purpose - nothing about it is falsified in the
 * database to hide it - so the loop scans past it with an offset instead of reselecting it forever,
 * and gives up for this call after {@link #MAX_SKIP_SCAN_FACTOR} times the batch size. The next
 * call starts over and reaches further only if earlier candidates became advanceable meanwhile.
 *
 * <p>The loop holds no transaction: whether one unit, one batch or the whole run commits together
 * is the caller's decision and differs between the three (the re-index and the Bestandslauf commit
 * per document, the remapping is one transaction by specification).
 */
public final class DocumentBatchLoop {

  /**
   * How many candidates one call may scan past, relative to its own batch size, before giving up.
   * Bounds the work a corpus consisting mostly of unadvanceable documents causes in one request.
   */
  public static final int MAX_SKIP_SCAN_FACTOR = 10;

  private DocumentBatchLoop() {}

  /** Selects the next candidates in a stable order, skipping {@code offset} of them. */
  @FunctionalInterface
  public interface Selection {
    List<UUID> select(int limit, int offset);
  }

  /**
   * Advances up to {@code batchSize} candidates and reports how many ended in each outcome. Every
   * constant of {@code outcomes} is present in the result, zero included; {@code skipOutcome} is
   * the one that does not count as progress and is scanned past instead.
   */
  public static <T extends Enum<T>> Map<T, Integer> run(
      int batchSize,
      Class<T> outcomes,
      T skipOutcome,
      Selection selection,
      Function<UUID, T> unit) {
    Map<T, Integer> counts = new EnumMap<>(outcomes);
    for (T outcome : outcomes.getEnumConstants()) {
      counts.put(outcome, 0);
    }
    if (batchSize <= 0) {
      return counts;
    }
    int advanced = 0;
    int skipped = 0;
    int maxSkips = batchSize * MAX_SKIP_SCAN_FACTOR;

    while (advanced < batchSize && skipped < maxSkips) {
      List<UUID> candidates = selection.select(batchSize, skipped);
      if (candidates.isEmpty()) {
        break;
      }
      boolean exhausted = true;
      for (UUID documentId : candidates) {
        if (advanced >= batchSize) {
          exhausted = false;
          break;
        }
        T outcome = unit.apply(documentId);
        counts.merge(outcome, 1, Integer::sum);
        if (outcome == skipOutcome) {
          skipped++;
        } else {
          advanced++;
        }
        if (skipped >= maxSkips) {
          exhausted = false;
          break;
        }
      }
      if (exhausted && candidates.size() < batchSize) {
        break;
      }
    }
    return counts;
  }
}
