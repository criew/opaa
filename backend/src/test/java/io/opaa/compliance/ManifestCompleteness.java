package io.opaa.compliance;

import io.opaa.compliance.ChangelogSchema.Table;
import io.opaa.compliance.ComplianceManifest.DataEntry;
import io.opaa.compliance.ComplianceManifest.NonPersonalTable;
import io.opaa.compliance.ComplianceManifest.PersonPath;
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

  private ManifestCompleteness() {}

  /** The tables of the delivered schema, all of them created by the changelog. */
  static Map<String, Table> deliveredSchema() {
    return new LinkedHashMap<>(ChangelogSchema.read());
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

  /**
   * Gaps in the path to a person ("Weg zur Person"): every table of a data entry states a path by
   * {@code columns}, {@code via} or both, or is {@code excluded}; a named column is a personal one,
   * a {@code via} ends in a table of the manifest with columns, an excluded column exists in the
   * table, and a data entry without table says why it is not part of the self-disclosure.
   */
  static List<String> personPathGaps(ComplianceManifest manifest) {
    List<String> gaps = new ArrayList<>();
    Map<String, TableRef> all = new LinkedHashMap<>();
    manifest.data().forEach(entry -> entry.tables().forEach(t -> all.putIfAbsent(t.name(), t)));
    for (DataEntry entry : manifest.data()) {
      String where = file(entry.area()) + ", data entry '" + entry.id() + "'";
      boolean reasoned = entry.personExcluded() != null && !entry.personExcluded().isBlank();
      if (entry.tables().isEmpty() && !reasoned) {
        gaps.add(
            "Data entry '"
                + entry.id()
                + "' in "
                + file(entry.area())
                + " has no table, so nothing in it reaches a person. Add to the entry why it is"
                + " not part of the self-disclosure:\n"
                + "    personExcluded: <reason, in German>");
      }
      if (!entry.tables().isEmpty() && entry.personExcluded() != null) {
        gaps.add(
            "Data entry '"
                + entry.id()
                + "' in "
                + file(entry.area())
                + " has tables but sets personExcluded. Remove personExcluded and decide per"
                + " table; a table left out of the self-disclosure says so under person:\n"
                + "        person:\n"
                + "          excluded: <reason, in German>");
      }
      List<String> siblings = entry.tables().stream().map(TableRef::name).toList();
      for (TableRef table : entry.tables()) {
        tablePathGaps(table, siblings, all, where, gaps);
      }
    }
    return gaps;
  }

  private static void tablePathGaps(
      TableRef table,
      List<String> siblings,
      Map<String, TableRef> all,
      String where,
      List<String> gaps) {
    PersonPath person = table.person();
    String head = "Table " + table.name() + " in " + where;
    if (person == null) {
      gaps.add(head + " has no path to a person. " + pathChoices(table, siblings));
      return;
    }
    boolean byColumns = person.columns() != null && !person.columns().isEmpty();
    boolean byVia = person.via() != null && !person.via().isBlank();
    boolean isExcluded = person.excluded() != null && !person.excluded().isBlank();
    if (isExcluded == (byColumns || byVia)) {
      gaps.add(
          head
              + (isExcluded
                  ? " states both a path to a person and an exclusion under person; keep one. "
                  : " states neither a path to a person nor an exclusion under person. ")
              + pathChoices(table, siblings));
      return;
    }
    if (byColumns) {
      for (String column : person.columns()) {
        if (!table.personalColumns().contains(column)) {
          gaps.add(
              "Column "
                  + table.name()
                  + "."
                  + column
                  + " under person.columns in "
                  + where
                  + " is no personal column of the table. Name the columns that identify the"
                  + " person:\n"
                  + "          columns: [<of "
                  + String.join(", ", table.personalColumns())
                  + ">]\n"
                  + "  or, if '"
                  + column
                  + "' names a person, move it into\n"
                  + "        personalColumns: "
                  + flow(append(table.personalColumns(), List.of(column))));
        }
      }
    }
    if (byVia) {
      String problem = viaProblem(table, all);
      if (problem != null) {
        gaps.add(
            head
                + " reaches the person via '"
                + person.via()
                + "', which "
                + problem
                + ". Give that table its own columns, or name a table that has them:\n"
                + "        person:\n"
                + "          via: "
                + tablesWithColumns(siblings, all));
      }
    }
    Map<String, String> excludedColumns = person.excludedColumnsOrEmpty();
    if (isExcluded && !excludedColumns.isEmpty()) {
      gaps.add(
          head
              + " is excluded as a whole; remove person.excludedColumns, it applies only to a"
              + " table with columns or via.");
      return;
    }
    for (String column : excludedColumns.keySet()) {
      if (!table.personalColumns().contains(column) && !nonNull(table).contains(column)) {
        gaps.add(
            "Column "
                + table.name()
                + "."
                + column
                + " under person.excludedColumns in "
                + where
                + " is no column of the table. Remove '"
                + column
                + "' there, or rename it if the column was renamed.");
      }
    }
  }

  /**
   * Why {@code table}'s {@code via} does not end in a table with columns, {@code null} when it
   * does; {@code all} holds every table of the manifest's data entries.
   */
  private static String viaProblem(TableRef table, Map<String, TableRef> all) {
    Set<String> seen = new LinkedHashSet<>(List.of(table.name()));
    TableRef current = table;
    while (true) {
      String via = current.person().via();
      TableRef next = all.get(via);
      String prefix = current == table ? "" : "leads to '" + via + "', which ";
      if (next == null) {
        return prefix + "is no table of a data entry";
      }
      if (!seen.add(via)) {
        return "leads back to '" + via + "' without reaching columns";
      }
      PersonPath path = next.person();
      if (path == null
          || (path.excluded() != null && !path.excluded().isBlank())
          || !path.reachesPerson()) {
        return prefix + "has no path of its own";
      }
      if (path.columns() != null && !path.columns().isEmpty()) {
        return null;
      }
      current = next;
    }
  }

  private static String tablesWithColumns(List<String> siblings, Map<String, TableRef> all) {
    List<String> names =
        siblings.stream()
            .map(all::get)
            .filter(t -> t != null && t.person() != null && t.person().columns() != null)
            .filter(t -> !t.person().columns().isEmpty())
            .map(TableRef::name)
            .toList();
    return names.isEmpty()
        ? "<table with columns, of this or another data entry>"
        : "<one of " + String.join(", ", names) + ", or a table of another data entry>";
  }

  private static String pathChoices(TableRef table, List<String> siblings) {
    List<String> others = siblings.stream().filter(n -> !n.equals(table.name())).toList();
    return "Add under table '"
        + table.name()
        + "' either the personal columns that identify the person\n"
        + "        person:\n"
        + "          columns: [<of "
        + String.join(", ", table.personalColumns())
        + ">]\n"
        + "  or, when a row reaches the person through another table, that table\n"
        + "        person:\n"
        + "          via: <"
        + (others.isEmpty() ? "" : "one of " + String.join(", ", others) + ", or ")
        + "a table of another data entry>\n"
        + "  (columns and via together state two paths) or, when the table is not part of the"
        + " self-disclosure,\n"
        + "        person:\n"
        + "          excluded: <reason, in German>\n"
        + "  With columns or via, a personal column left out of the self-disclosure goes under\n"
        + "          excludedColumns:\n"
        + "            <column>: <reason, in German>";
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
            + ", table '"
            + table.name()
            + "', ";
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
        + "either, if the column identifies a person or records what a person did or when (id,"
        + " name, address, free text, time of an action), replace\n"
        + "        personalColumns: "
        + flow(append(place.personal(), missing))
        + "\n  or otherwise replace\n"
        + "        nonPersonalColumns: "
        + flow(append(place.nonPersonal(), missing))
        + (missing.size() > 1
            ? "\n  Several new columns: append each to the list that fits it."
            : "");
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
