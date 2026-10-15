package io.opaa.compliance;

import io.opaa.compliance.ChangelogSchema.Table;
import io.opaa.compliance.ComplianceManifest.DataEntry;
import io.opaa.compliance.ComplianceManifest.NonPersonalTable;
import io.opaa.compliance.ComplianceManifest.PromptEntry;
import io.opaa.compliance.ComplianceManifest.TableRef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares the manifest with the code in both directions and words every gap as an instruction:
 * what is missing or orphaned, in which file, and the YAML to paste. Checks existence only, never
 * whether an entry is right (ADR-0052, Entscheidung 7).
 */
final class ManifestCompleteness {

  static final String MANIFEST_DIR = "backend/src/main/resources/compliance/manifest/";

  static final String CONVENTION = "backend/docs/compliance-manifest.md";

  /**
   * Tables no changelog creates: Spring AI's {@code PgVectorStore} creates {@code vector_store} at
   * start-up with these columns.
   */
  static final Map<String, List<String>> TABLES_OUTSIDE_THE_CHANGELOG =
      Map.of("vector_store", List.of("id", "content", "metadata", "embedding"));

  private ManifestCompleteness() {}

  /** The tables of the delivered schema: the changelog's and those created outside it. */
  static Map<String, Table> deliveredSchema() {
    Map<String, Table> schema = new LinkedHashMap<>(ChangelogSchema.read());
    TABLES_OUTSIDE_THE_CHANGELOG.forEach(
        (name, columns) -> {
          Map<String, String> sources = new LinkedHashMap<>();
          columns.forEach(column -> sources.put(column, "Spring AI PgVectorStore"));
          schema.put(name, new Table(name, "Spring AI PgVectorStore", sources));
        });
    return schema;
  }

  /** The gaps as one failure message, closed by the pointer to the convention. */
  static String report(String what, List<String> gaps) {
    return "The compliance manifest does not match the "
        + what
        + " ("
        + gaps.size()
        + " gap"
        + (gaps.size() == 1 ? "" : "s")
        + "):\n\n"
        + String.join("\n\n", gaps)
        + "\n\nConvention: "
        + CONVENTION;
  }

  /**
   * Gaps between the constants of {@code type} and the entries of {@code section}.
   *
   * @param idToArea the entries of the section, id to area
   * @param mandatory the mandatory field a new entry needs besides its id, with a placeholder
   */
  static List<String> enumGaps(
      Class<? extends Enum<?>> type,
      String section,
      Map<String, String> idToArea,
      String mandatory) {
    List<String> gaps = new ArrayList<>();
    String home = mostFrequent(idToArea.values());
    Set<String> constants = new LinkedHashSet<>();
    Arrays.stream(type.getEnumConstants()).forEach(constant -> constants.add(constant.name()));
    for (String constant : constants) {
      if (!idToArea.containsKey(constant)) {
        gaps.add(
            type.getSimpleName()
                + "."
                + constant
                + " has no manifest entry. Add to "
                + file(home)
                + ", section "
                + section
                + ":\n"
                + "  - id: "
                + constant
                + "\n    "
                + mandatory);
      }
    }
    idToArea.forEach(
        (id, area) -> {
          if (!constants.contains(id)) {
            gaps.add(
                section
                    + " entry '"
                    + id
                    + "' in "
                    + file(area)
                    + " names no "
                    + type.getSimpleName()
                    + " constant. Remove the entry, or rename its id if the constant was renamed.");
          }
        });
    return gaps;
  }

  /** Prompts whose text at their location no longer has the checksum the manifest records. */
  static List<String> promptGaps(List<PromptEntry> prompts) {
    List<String> gaps = new ArrayList<>();
    for (PromptEntry prompt : prompts) {
      String actual = PromptChecksum.of(prompt);
      if (!actual.equals(prompt.sha256())) {
        gaps.add(
            "Prompt '"
                + prompt.id()
                + "' ("
                + (prompt.databaseDefault() != null
                    ? prompt.databaseDefault()
                    : String.join(", ", prompt.classes()))
                + ") changed. Review the wording - the change appears in the update delta -"
                + " then set in "
                + file(prompt.area())
                + ", entry '"
                + prompt.id()
                + "':\n"
                + "    sha256: \""
                + actual
                + "\"");
      }
    }
    return gaps;
  }

  /** Gaps between the tables and columns of {@code schema} and those the manifest names. */
  static List<String> schemaGaps(ComplianceManifest manifest, Map<String, Table> schema) {
    List<String> gaps = new ArrayList<>();
    Map<String, Place> places = places(manifest, gaps);

    for (Table table : schema.values()) {
      Place place = places.get(table.name());
      if (place == null) {
        gaps.add(missingTable(table, schema, places));
        continue;
      }
      List<String> missing =
          table.columns().keySet().stream().filter(column -> !place.lists(column)).toList();
      if (!missing.isEmpty()) {
        gaps.add(missingColumns(table, missing, place));
      }
      for (String column : place.listed()) {
        if (!table.columns().containsKey(column)) {
          gaps.add(
              "Column "
                  + table.name()
                  + "."
                  + column
                  + " in "
                  + place.where()
                  + " does not exist in the schema. Remove '"
                  + column
                  + "' there, or rename it if the column was renamed.");
        }
      }
    }
    places.forEach(
        (name, place) -> {
          if (!schema.containsKey(name)) {
            gaps.add(
                "Table "
                    + name
                    + " in "
                    + place.where()
                    + " does not exist in the schema. Remove it there; a data entry left without"
                    + " tables and without storage outside the database goes as well.");
          }
        });
    return gaps;
  }

  private static String missingColumns(Table table, List<String> missing, Place place) {
    String added =
        missing.stream()
            .map(column -> column + " (" + table.columns().get(column) + ")")
            .collect(Collectors.joining(", "));
    String head =
        "Table "
            + table.name()
            + " has columns without manifest entry: "
            + added
            + ".\n  In "
            + place.where()
            + ", ";
    if (place.dataId() == null) {
      return head
          + "if no new column points at a person, set:\n"
          + "    "
          + table.name()
          + ": "
          + flow(append(place.nonPersonal(), missing))
          + "\n  Otherwise the table holds personal data now: move it into the tables of a data"
          + " entry as\n"
          + "      - name: "
          + table.name()
          + "\n        personalColumns: "
          + flow(missing)
          + "\n        nonPersonalColumns: "
          + flow(place.nonPersonal());
    }
    return head
        + "add each column to the list that fits it - personalColumns if it identifies a person or"
        + " records what a person did or when (id, name, address, free text, time of an action),"
        + " nonPersonalColumns otherwise:\n"
        + "        personalColumns: "
        + flow(append(place.personal(), missing))
        + "\n        nonPersonalColumns: "
        + flow(append(place.nonPersonal(), missing));
  }

  private static String missingTable(
      Table table, Map<String, Table> schema, Map<String, Place> places) {
    List<String> columns = List.copyOf(table.columns().keySet());
    String area = suggestedArea(table, schema, places);
    return "Table "
        + table.name()
        + " ("
        + table.file()
        + ") has no manifest entry. Add it in "
        + file(area)
        + (area.equals("<area>") ? "" : " (holds most tables of the same changelog directory)")
        + ".\n  If no column points at a person:\n"
        + "    nonPersonalTables:\n"
        + "      "
        + table.name()
        + ": "
        + flow(columns)
        + "\n  Otherwise under tables of the data entry whose data it holds, or of a new data"
        + " entry, splitting the columns:\n"
        + "      - name: "
        + table.name()
        + "\n        personalColumns: [<columns that identify a person or record what a person"
        + " did or when>]"
        + "\n        nonPersonalColumns: [<the others of "
        + String.join(", ", columns)
        + ">]";
  }

  private static String suggestedArea(
      Table table, Map<String, Table> schema, Map<String, Place> places) {
    String directory = directory(table.file());
    List<String> areas =
        schema.values().stream()
            .filter(other -> directory.equals(directory(other.file())))
            .map(other -> places.get(other.name()))
            .filter(Objects::nonNull)
            .map(Place::area)
            .toList();
    return areas.isEmpty() ? "<area>" : mostFrequent(areas);
  }

  private static String directory(String file) {
    int slash = file.lastIndexOf('/');
    return slash < 0 ? file : file.substring(0, slash);
  }

  /** Where each table is named; a table named twice is a gap and keeps its first place. */
  private static Map<String, Place> places(ComplianceManifest manifest, List<String> gaps) {
    Map<String, Place> places = new LinkedHashMap<>();
    for (DataEntry entry : manifest.data()) {
      for (TableRef ref : entry.tables()) {
        add(
            places,
            new Place(entry.area(), entry.id(), ref.name(), ref.personalColumns(), nonNull(ref)),
            gaps);
      }
    }
    for (NonPersonalTable table : manifest.nonPersonalTables()) {
      add(places, new Place(table.area(), null, table.table(), List.of(), table.columns()), gaps);
    }
    return places;
  }

  private static List<String> nonNull(TableRef ref) {
    return ref.nonPersonalColumns() == null ? List.of() : ref.nonPersonalColumns();
  }

  private static void add(Map<String, Place> places, Place place, List<String> gaps) {
    Place first = places.putIfAbsent(place.table(), place);
    if (first != null) {
      gaps.add(
          "Table "
              + place.table()
              + " is named twice: in "
              + first.where()
              + " and in "
              + place.where()
              + ". Keep one.");
    }
    Set<String> seen = new LinkedHashSet<>();
    for (String column : place.listedWithRepeats()) {
      if (!seen.add(column)) {
        gaps.add(
            "Column "
                + place.table()
                + "."
                + column
                + " is listed twice in "
                + place.where()
                + ". Keep it once, in personalColumns or nonPersonalColumns.");
      }
    }
  }

  /** The most frequent value, the alphabetically first on a tie. */
  private static String mostFrequent(Collection<String> values) {
    return values.stream()
        .collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting()))
        .entrySet()
        .stream()
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElse("<area>");
  }

  private static String file(String area) {
    return MANIFEST_DIR + area + ".yaml";
  }

  private static List<String> append(List<String> list, List<String> more) {
    List<String> result = new ArrayList<>(list);
    result.addAll(more);
    return result;
  }

  private static String flow(List<String> values) {
    return "[" + String.join(", ", values) + "]";
  }

  /** Where the manifest names a table, with the columns it lists. */
  private record Place(
      String area, String dataId, String table, List<String> personal, List<String> nonPersonal) {

    Place {
      personal = personal == null ? List.of() : personal;
      nonPersonal = nonPersonal == null ? List.of() : nonPersonal;
    }

    boolean lists(String column) {
      return personal.contains(column) || nonPersonal.contains(column);
    }

    Set<String> listed() {
      return new LinkedHashSet<>(listedWithRepeats());
    }

    List<String> listedWithRepeats() {
      return append(personal, nonPersonal);
    }

    String where() {
      return file(area)
          + (dataId == null ? ", nonPersonalTables" : ", data entry '" + dataId + "'");
    }
  }
}
