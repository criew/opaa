package io.opaa.indexing.maintenance;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The chargen loop the resumable document runs share: pipeline re-index ({@link
 * PipelineReindexService}), the deterministic Bestandslauf ({@code MetadataBackfillService}), the
 * Kontextpräfix rerun ({@link ContextPrefixRerunService}) and the schema changes of a library field
 * ({@code LibraryMetadataSchemaChangeService}). Selection and processing unit are the caller's; the
 * loop owns only the mechanics they all need.
 *
 * <p><b>Every call terminates and every call makes progress.</b> A candidate the unit cannot
 * advance right now stays in the candidate set on purpose - nothing about it is falsified in the
 * database to hide it - so the loop scans past it with an offset instead of reselecting it forever,
 * and gives up for this call after {@link #MAX_SKIP_SCAN_FACTOR} times the batch size. The next
 * call starts over and reaches further only if earlier candidates became advanceable meanwhile. A
 * unit may also end the call outright by reporting the stop outcome of the overload below.
 *
 * <p>The loop holds no transaction: whether one unit, one batch or the whole run commits together
 * is the caller's decision. Every caller today commits per document, which is what makes an
 * interrupted run keep what it has done.
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
    return run(batchSize, outcomes, skipOutcome, null, selection, unit);
  }

  /**
   * With a {@code stopOutcome} ({@code null} for none): an outcome that ends this call the moment
   * it occurs. It is counted like every other one, but the candidates behind it are left untouched
   * instead of being scanned past - for a unit that has just learned of a condition every remaining
   * candidate would run into as well, which would only cost time.
   */
  public static <T extends Enum<T>> Map<T, Integer> run(
      int batchSize,
      Class<T> outcomes,
      T skipOutcome,
      T stopOutcome,
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
        if (outcome == stopOutcome) {
          return counts;
        }
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
