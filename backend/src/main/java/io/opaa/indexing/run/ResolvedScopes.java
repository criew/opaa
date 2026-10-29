package io.opaa.indexing.run;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.spi.SourceSyncState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The scope "Alle" of a library: every run resolves it anew to what the access sees, and this notes
 * in the run's protocol how many scopes that is, which appeared or vanished since the resolution
 * the sync state remembers, and which the access sees but the library leaves out. An event run
 * notes only a change. The caller saves the state with the rest of its run.
 */
public final class ResolvedScopes {

  /** At most this many scopes are named in one note; the rest are counted. */
  static final int NAMED = 20;

  /** A full run removes by absence, so what a vanished scope held goes in this very run. */
  static final String REMOVED_NOW =
      "; der Zugang sieht sie nicht mehr, was daraus indiziert ist, entfernt dieser Vollabgleich";

  static final String REMOVED_LATER =
      "; der Zugang sieht sie nicht mehr, was daraus indiziert ist, entfernt der nächste"
          + " Vollabgleich";

  /** What changed against the remembered resolution; both empty in a library's first such run. */
  public record Change(Set<String> added, Set<String> removed) {

    public Change {
      added = Set.copyOf(added);
      removed = Set.copyOf(removed);
    }

    boolean isEmpty() {
      return added.isEmpty() && removed.isEmpty();
    }
  }

  private ResolvedScopes() {}

  /** {@link #note(IndexingRun, SourceSyncState, Map, Map, String, String)} leaving nothing out. */
  public static Change note(
      IndexingRun run,
      SourceSyncState state,
      Map<String, String> current,
      String one,
      String many) {
    return note(run, state, current, Map.of(), one, many);
  }

  /**
   * Notes {@code current} (scope key to shown name, {@code null} for none) in the protocol of
   * {@code run} against the resolution {@code state} remembers, and remembers {@code current} in
   * its place.
   *
   * @param outside the scopes the access sees but the library leaves out, key to German reason
   * @param one the noun of one scope, as in "1 Space"
   * @param many the noun of several, as in "3 Spaces"
   */
  public static Change note(
      IndexingRun run,
      SourceSyncState state,
      Map<String, String> current,
      Map<String, String> outside,
      String one,
      String many) {
    return note(run, state, current, outside, one, many, run.runMode() == IndexingRunMode.EVENT);
  }

  /**
   * {@link #note(IndexingRun, SourceSyncState, Map, Map, String, String)}, noting nothing without a
   * change when {@code changesOnly} - for a run started by a notification about single items.
   */
  public static Change note(
      IndexingRun run,
      SourceSyncState state,
      Map<String, String> current,
      Map<String, String> outside,
      String one,
      String many,
      boolean changesOnly) {
    Map<String, String> previous = state.resolvedScopes();
    Map<String, String> now = new TreeMap<>(current);
    Set<String> added = new LinkedHashSet<>();
    Set<String> removed = new LinkedHashSet<>();
    Map<String, String> before = previous == null ? Map.of() : new TreeMap<>(previous);
    if (previous != null) {
      now.keySet().stream().filter(key -> !before.containsKey(key)).forEach(added::add);
      before.keySet().stream().filter(key -> !now.containsKey(key)).forEach(removed::add);
    }
    Change change = new Change(added, removed);
    state.rememberResolvedScopes(now);
    if (changesOnly && change.isEmpty()) {
      return change;
    }
    record(run, "Umfang „Alle“: " + counted(now.size(), one, many));
    if (!added.isEmpty()) {
      record(run, "Neu im Umfang: " + listed(added, key -> name(now, key), one, many));
    }
    if (!removed.isEmpty()) {
      String suffix = run.runMode() == IndexingRunMode.FULL ? REMOVED_NOW : REMOVED_LATER;
      record(
          run,
          "Nicht mehr im Umfang: " + listed(removed, key -> name(before, key), one, many) + suffix);
    }
    if (!outside.isEmpty()) {
      Map<String, String> reasons = new TreeMap<>(outside);
      record(
          run,
          "Nicht im Umfang: "
              + listed(
                  reasons.keySet(), key -> "„" + key + "“ (" + reasons.get(key) + ")", one, many));
    }
    return change;
  }

  private static void record(IndexingRun run, String message) {
    run.events().recordRunNote(IndexingEventCategory.SUMMARY, message);
  }

  private static String name(Map<String, String> names, String key) {
    String name = names.get(key);
    return "„" + (name == null || name.isBlank() ? key : name) + "“";
  }

  /** "2 Spaces – „Technik“, „Vertrieb“", the first {@link #NAMED} shown, the rest counted. */
  static String listed(
      Set<String> keys, Function<String, String> shownAs, String one, String many) {
    List<String> shown = new ArrayList<>();
    for (String key : keys) {
      if (shown.size() == NAMED) {
        break;
      }
      shown.add(shownAs.apply(key));
    }
    int rest = keys.size() - shown.size();
    return counted(keys.size(), one, many)
        + " – "
        + String.join(", ", shown)
        + (rest > 0 ? " und " + rest + " weitere" : "");
  }

  private static String counted(int count, String one, String many) {
    return count + " " + (count == 1 ? one : many);
  }
}
