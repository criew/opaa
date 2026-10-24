package io.opaa.compliance;

import io.opaa.compliance.ManifestSnapshot.Origin;
import io.opaa.compliance.StageRules.Assessment;
import io.opaa.compliance.StageRules.Context;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The cumulative delta between the manifests of two versions, compared directly rather than as a
 * chain of release deltas: an entry added and removed again in between does not appear, a value
 * changed several times appears once with its first and last state. Each change carries the
 * proposal of {@link StageRules}. The order of {@link #changes()} depends only on the two
 * manifests.
 *
 * @param from the older version
 * @param to the newer version
 * @param changes ordered by group, section, entry and field
 */
public record ManifestDelta(Origin from, Origin to, List<Change> changes) {

  /** The pseudo section of the area titles. */
  static final String AREAS = "areas";

  /** Known sections in the order they appear within a group; others follow alphabetically. */
  private static final List<String> SECTION_ORDER =
      List.of(
          "data",
          "nonPersonalTables",
          "auditEvents",
          "roles",
          "capabilities",
          "dataFlows",
          "settings",
          "prompts",
          "aiFunctions",
          AREAS);

  public ManifestDelta {
    changes = List.copyOf(changes);
  }

  /** Whether an entry or field appears, disappears or differs. */
  public enum Kind {
    ADDED,
    REMOVED,
    CHANGED
  }

  /** The kinds of change an authority reads separately, in the order of the delta. */
  public enum Group {
    DATA("Daten"),
    AUDIT_EVENTS("Protokollereignisse"),
    ROLES("Rollen und Sichtbarkeit"),
    DATA_FLOWS("Datenflüsse nach außen"),
    RETENTION("Fristen"),
    DEFAULTS("Voreinstellungen"),
    MODELS_AND_PROMPTS("Standardmodelle und Prompts"),
    AI_FUNCTIONS("KI-Funktionen"),
    STRUCTURE("Gliederung des Manifests"),
    OTHER("Weitere Angaben");

    private final String title;

    Group(String title) {
      this.title = title;
    }

    /** The German heading. */
    public String title() {
      return title;
    }
  }

  /**
   * One change.
   *
   * @param section the manifest section ({@code data}, {@code settings}, ...) or {@code areas}
   * @param id the identifier of the entry (id, setting key, table or area)
   * @param title the entry's title, its id where it has none
   * @param field the field path for {@link Kind#CHANGED}, {@code null} otherwise
   * @param before the field's value, the whole entry for {@link Kind#REMOVED}, {@code null} when
   *     absent
   * @param after the field's value, the whole entry for {@link Kind#ADDED}, {@code null} when
   *     absent
   */
  public record Change(
      Group group,
      String section,
      String id,
      String title,
      Kind kind,
      String field,
      Object before,
      Object after,
      Assessment assessment) {}

  /** The delta from {@code from} to {@code to}. */
  public static ManifestDelta between(ManifestSnapshot from, ManifestSnapshot to) {
    Context context = context(from, to);
    SortedMap<String, SortedMap<String, SortedMap<String, Object>>> before = withAreas(from);
    SortedMap<String, SortedMap<String, SortedMap<String, Object>>> after = withAreas(to);
    List<Change> changes = new ArrayList<>();
    for (String section : union(before.keySet(), after.keySet())) {
      SortedMap<String, SortedMap<String, Object>> old =
          before.getOrDefault(section, new TreeMap<>());
      SortedMap<String, SortedMap<String, Object>> now =
          after.getOrDefault(section, new TreeMap<>());
      for (String id : union(old.keySet(), now.keySet())) {
        Map<String, Object> o = old.get(id);
        Map<String, Object> n = now.get(id);
        String title = title(id, n == null ? o : n);
        Group group = group(section, id, context);
        if (o == null) {
          changes.add(
              new Change(
                  group,
                  section,
                  id,
                  title,
                  Kind.ADDED,
                  null,
                  null,
                  n,
                  StageRules.added(section, id, n, context)));
        } else if (n == null) {
          changes.add(
              new Change(
                  group,
                  section,
                  id,
                  title,
                  Kind.REMOVED,
                  null,
                  o,
                  null,
                  StageRules.removed(section, id, o, context)));
        } else {
          for (String field : union(o.keySet(), n.keySet())) {
            Object b = o.get(field);
            Object a = n.get(field);
            if (!same(b, a)) {
              changes.add(
                  new Change(
                      group,
                      section,
                      id,
                      title,
                      Kind.CHANGED,
                      field,
                      b,
                      a,
                      StageRules.changed(section, id, field, b, a, o, n, context)));
            }
          }
        }
      }
    }
    changes.sort(
        Comparator.comparing(Change::group)
            .thenComparing(change -> sectionRank(change.section()))
            .thenComparing(Change::section)
            .thenComparing(Change::id)
            .thenComparing(change -> Objects.toString(change.field(), "")));
    return new ManifestDelta(from.origin(), to.origin(), changes);
  }

  /** The context of the stage rules for changes between {@code from} and {@code to}. */
  static Context context(ManifestSnapshot from, ManifestSnapshot to) {
    return new Context(union(retentionSettings(from), retentionSettings(to)));
  }

  /** The group a change of {@code id} in {@code section} belongs to. */
  static Group group(String section, String id, Context context) {
    return switch (section) {
      case "data", "nonPersonalTables" -> Group.DATA;
      case "auditEvents" -> Group.AUDIT_EVENTS;
      case "roles", "capabilities" -> Group.ROLES;
      case "dataFlows" -> Group.DATA_FLOWS;
      case "settings" -> {
        if (context.isRetention(id)) {
          yield Group.RETENTION;
        }
        yield StageRules.isModel(id) || StageRules.isAddress(id)
            ? Group.MODELS_AND_PROMPTS
            : Group.DEFAULTS;
      }
      case "prompts" -> Group.MODELS_AND_PROMPTS;
      case "aiFunctions" -> Group.AI_FUNCTIONS;
      case AREAS -> Group.STRUCTURE;
      default -> Group.OTHER;
    };
  }

  /**
   * An absent list and an empty one are the same, and lists compare regardless of order: every list
   * of the manifest (columns, roles, access paths, settings, prompts) is a set in its meaning.
   */
  private static boolean same(Object before, Object after) {
    if (before instanceof List<?> || after instanceof List<?>) {
      return sorted(before).equals(sorted(after));
    }
    return Objects.equals(before, after);
  }

  private static List<String> sorted(Object value) {
    return value instanceof List<?> list
        ? list.stream().map(String::valueOf).sorted().toList()
        : value == null ? List.of() : List.of(String.valueOf(value));
  }

  private static String title(String id, Map<String, Object> entry) {
    return entry.get("title") instanceof String title && !title.isBlank() ? title : id;
  }

  private static int sectionRank(String section) {
    int rank = SECTION_ORDER.indexOf(section);
    return rank < 0 ? SECTION_ORDER.size() : rank;
  }

  private static SortedMap<String, SortedMap<String, SortedMap<String, Object>>> withAreas(
      ManifestSnapshot snapshot) {
    SortedMap<String, SortedMap<String, SortedMap<String, Object>>> sections =
        new TreeMap<>(snapshot.sections());
    SortedMap<String, SortedMap<String, Object>> areas = new TreeMap<>();
    snapshot
        .areas()
        .forEach(
            (area, title) -> {
              SortedMap<String, Object> fields = new TreeMap<>();
              fields.put("title", title);
              areas.put(area, fields);
            });
    sections.put(AREAS, areas);
    return sections;
  }

  private static Set<String> retentionSettings(ManifestSnapshot snapshot) {
    Set<String> keys = new HashSet<>();
    for (String section : List.of("data", "auditEvents")) {
      snapshot
          .sections()
          .getOrDefault(section, new TreeMap<>())
          .values()
          .forEach(
              entry -> {
                if (entry.get("retention.settings") instanceof List<?> settings) {
                  settings.forEach(key -> keys.add(String.valueOf(key)));
                }
              });
    }
    return keys;
  }

  /** Sorted, so every iteration over sections, entries and fields is stable. */
  private static TreeSet<String> union(Set<String> a, Set<String> b) {
    TreeSet<String> union = new TreeSet<>(a);
    union.addAll(b);
    return union;
  }
}
