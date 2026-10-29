package io.opaa.indexing.run;

import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.spi.SourceSyncState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The scope "Alle" of a library: every run resolves it anew to what the access sees, and this notes
 * in the run's protocol how many scopes that is and which appeared or vanished since the resolution
 * the sync state remembers. The caller saves the state with the rest of its run.
 */
public final class ResolvedScopes {

  /** At most this many scopes are named in one note; the rest are counted. */
  static final int NAMED = 20;

  static final String REMOVED_SUFFIX =
      "; der Zugang sieht sie nicht mehr, was daraus indiziert ist, entfernt der nächste"
          + " Vollabgleich";

  /** What changed against the remembered resolution; both empty in a library's first such run. */
  public record Change(Set<String> added, Set<String> removed) {

    public Change {
      added = Set.copyOf(added);
      removed = Set.copyOf(removed);
    }
  }

  private ResolvedScopes() {}

  /**
   * Notes {@code current} (scope key to shown name, {@code null} for none) in the protocol of
   * {@code run} against the resolution {@code state} remembers, and remembers {@code current} in
   * its place.
   *
   * @param one the noun of one scope, as in "1 Space"
   * @param many the noun of several, as in "3 Spaces"
   */
  public static Change note(
      IndexingRun run,
      SourceSyncState state,
      Map<String, String> current,
      String one,
      String many) {
    Map<String, String> previous = state.resolvedScopes();
    Map<String, String> now = new TreeMap<>(current);
    run.events()
        .recordRunNote(
            IndexingEventCategory.SUMMARY, "Umfang „Alle“: " + counted(now.size(), one, many));
    Set<String> added = new LinkedHashSet<>();
    Set<String> removed = new LinkedHashSet<>();
    if (previous != null) {
      Map<String, String> before = new TreeMap<>(previous);
      now.keySet().stream().filter(key -> !before.containsKey(key)).forEach(added::add);
      before.keySet().stream().filter(key -> !now.containsKey(key)).forEach(removed::add);
      if (!added.isEmpty()) {
        run.events()
            .recordRunNote(
                IndexingEventCategory.SUMMARY, "Neu im Umfang: " + listed(added, now, one, many));
      }
      if (!removed.isEmpty()) {
        run.events()
            .recordRunNote(
                IndexingEventCategory.SUMMARY,
                "Nicht mehr im Umfang: " + listed(removed, before, one, many) + REMOVED_SUFFIX);
      }
    }
    state.rememberResolvedScopes(now);
    return new Change(added, removed);
  }

  /** "2 Spaces – „Technik“, „Vertrieb“", the first {@link #NAMED} by name, the rest counted. */
  static String listed(Set<String> keys, Map<String, String> names, String one, String many) {
    List<String> shown = new ArrayList<>();
    for (String key : keys) {
      if (shown.size() == NAMED) {
        break;
      }
      String name = names.get(key);
      shown.add("„" + (name == null || name.isBlank() ? key : name) + "“");
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
