package io.opaa.compliance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The stage rules of the manifest delta: for each change, the stage proposed under the model of IT
 * framework service agreements (Bagatelle, Anzeige, Zustimmung) with its classes Z1 to Z7 and
 * reason, and whether the record of processing activities (VVT), the data protection impact
 * assessment (DSFA) and the security concept are concerned. This class is the only place in the
 * code that holds them; the specification states them in "Die Stufenregeln" of {@code
 * docs/features/behoerdenunterlagen.md} and maps manifest changes to them in "Zuordnung im
 * Werkzeug". In doubt, the proposal is Zustimmung with {@link Assessment#undecided()} set.
 */
public final class StageRules {

  /** A stage of the service agreement model, ascending in weight. */
  public enum Stage {
    BAGATELLE("Bagatelle"),
    ANZEIGE("Anzeige"),
    ZUSTIMMUNG("Zustimmung");

    private final String title;

    Stage(String title) {
      this.title = title;
    }

    /** The German name. */
    public String title() {
      return title;
    }
  }

  /**
   * The proposal for one change.
   *
   * @param classes the Zustimmung classes it meets ({@code Z1} to {@code Z7}); empty for the lower
   *     stages and when no class can be named
   * @param reason why, in plain German
   * @param undecided the change fits no rule unambiguously; the stage is Zustimmung for that reason
   * @param vvt the record of processing activities is to be adapted (purpose, data category,
   *     recipient or retention changes)
   * @param dsfa the impact assessment is to be reviewed; a heuristic: every Zustimmung
   * @param securityConcept the security concept is to be updated (new data flows, egress targets,
   *     roles or audit events)
   */
  public record Assessment(
      Stage stage,
      List<String> classes,
      String reason,
      boolean undecided,
      boolean vvt,
      boolean dsfa,
      boolean securityConcept) {

    public Assessment {
      classes = List.copyOf(classes);
    }
  }

  /**
   * What the rules need to know beyond a single entry.
   *
   * @param retentionSettings keys of the settings a datum or audit event names as its retention
   *     setting, in either version
   */
  public record Context(Set<String> retentionSettings) {

    public Context {
      retentionSettings = Set.copyOf(retentionSettings);
    }

    boolean isRetention(String key) {
      return retentionSettings.contains(key);
    }
  }

  private static final Pattern ADDRESS = Pattern.compile(".*(url|uri|endpoint|base)");
  private static final Pattern MODEL = Pattern.compile("model([-_]identifier)?");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final List<String> PERSONAL_REFERENCE_RANK =
      List.of("NONE", "IDENTIFIABLE", "EMPLOYEE");

  /** List fields holding free text, where a reworded item reads as one added and one removed. */
  private static final Set<String> FREE_TEXT_LISTS = Set.of("visibility", "readers", "notes");

  private StageRules() {}

  /** Whether a setting key is the address a model role or connector sends data to. */
  static boolean isAddress(String key) {
    return ADDRESS.matcher(lastSegment(key)).matches();
  }

  /** Whether a setting key is the model of a model role. */
  static boolean isModel(String key) {
    return MODEL.matcher(lastSegment(key)).matches();
  }

  /** A whole entry of {@code section} that only the newer version has. */
  public static Assessment added(
      String section, String id, Map<String, Object> entry, Context context) {
    return switch (section) {
      case "data" ->
          personal(entry)
              ? zustimmung("Z1", "Neue personenbezogene Daten.", true, false)
              : hasTable(entry)
                  ? bagatelle("Neue Tabelle ohne Personenbezug.")
                  : undecided(
                      List.of(),
                      "Neue Daten ohne Personenbezug außerhalb der Datenbank; die Bagatell-Liste"
                          + " nennt nur Tabellen und Spalten.",
                      true,
                      false);
      case "nonPersonalTables" -> bagatelle("Neue Tabelle ohne Personenbezug.");
      case "auditEvents" ->
          zustimmung("Z1", "Neues Protokollereignis: Es hält fest, was Personen tun.", true, true);
      case "roles", "capabilities" ->
          zustimmung(
              "Z2", "Neue Rolle oder Fähigkeit, die etwas über andere Personen sieht.", true, true);
      case "settings" -> {
        if (context.isRetention(id)) {
          yield zustimmung(List.of("Z4", "Z7"), "Neue Frist mit Voreinstellung.", true, false);
        }
        if (isAddress(id)) {
          yield zustimmung(
              List.of("Z5", "Z7"),
              "Neue Voreinstellung für ein Ziel ausgehender Daten.",
              true,
              true);
        }
        yield zustimmung("Z7", "Neue Voreinstellung mit Datenwirkung.", false, false);
      }
      case "prompts" ->
          undecided(
              List.of("Z3"),
              "Neuer System-Prompt; ob er eine neue Auswertung bewirkt, lässt sich aus dem"
                  + " Manifest nicht ablesen.",
              false,
              false);
      case "dataFlows" -> zustimmung("Z5", "Neuer Datenfluss nach außen.", true, true);
      case "aiFunctions" ->
          zustimmung(
              "Z6",
              "Neue KI-Funktion; die Einstufung nach KI-Verordnung ändert sich.",
              false,
              false);
      case "areas" -> bagatelle("Neuer Bereich des Manifests; seine Einträge erscheinen einzeln.");
      default -> undecided(List.of(), "Unbekannte Eintragsart.", false, false);
    };
  }

  /** A whole entry of {@code section} that only the older version has. */
  public static Assessment removed(
      String section, String id, Map<String, Object> entry, Context context) {
    return switch (section) {
      case "data" -> anzeige("Entfallene Daten.", true);
      case "nonPersonalTables" -> anzeige("Entfallene Tabelle ohne Personenbezug.", false);
      case "auditEvents" -> anzeige("Entfallenes Protokollereignis.", true);
      case "roles", "capabilities" ->
          anzeige("Entfallene Rolle oder Fähigkeit: Die Sichtbarkeit wird eingeschränkt.", true);
      case "settings" ->
          undecided(
              List.of(context.isRetention(id) ? "Z4" : "Z7"),
              "Eine Voreinstellung entfällt; ob das Verhalten dahinter wegfällt oder fest"
                  + " eingestellt wird, lässt sich aus dem Manifest nicht ablesen.",
              context.isRetention(id),
              false);
      case "prompts" -> anzeige("Entfallener System-Prompt.", false);
      case "dataFlows" -> anzeige("Entfallener Datenfluss nach außen.", true);
      case "aiFunctions" -> anzeige("Entfallene KI-Funktion.", false);
      case "areas" ->
          bagatelle("Entfallener Bereich des Manifests; seine Einträge erscheinen einzeln.");
      default -> undecided(List.of(), "Unbekannte Eintragsart.", false, false);
    };
  }

  /**
   * One field of an entry both versions have. {@code before} and {@code after} are a {@code
   * String}, a {@code List<String>} or {@code null} when the field is absent on that side; {@code
   * entryBefore} and {@code entryAfter} are the whole entries.
   */
  public static Assessment changed(
      String section,
      String id,
      String field,
      Object before,
      Object after,
      Map<String, Object> entryBefore,
      Map<String, Object> entryAfter,
      Context context) {
    if (before instanceof String b
        && after instanceof String a
        && normalized(b).equals(normalized(a))) {
      return bagatelle("Nur die Schreibweise ändert sich (Leerzeichen, Zeilenumbruch).");
    }
    if (field.equals("area")) {
      return bagatelle("Der Eintrag steht in einem anderen Bereich des Manifests.");
    }
    if (field.equals("title")) {
      return bagatelle("Nur die Bezeichnung ändert sich.");
    }
    ListChange list = ListChange.of(before, after);
    return switch (section) {
      case "data", "auditEvents" -> dataFact(field, before, after, list);
      case "nonPersonalTables" ->
          field.equals("columns")
              ? (list.removed().isEmpty()
                  ? bagatelle("Neue Spalte ohne Personenbezug.")
                  : anzeige("Entfallene Spalten ohne Personenbezug.", false))
              : undecided(List.of(), "Angabe ohne Regel.", false, false);
      case "roles", "capabilities" ->
          field.equals("seesAboutOthers")
              ? undecided(
                  List.of("Z2"),
                  "Was die Rolle über andere sieht, ist neu beschrieben; ob sie mehr sieht, lässt"
                      + " sich aus dem Text nicht ablesen.",
                  true,
                  false)
              : undecided(List.of(), "Geänderter Hinweis.", false, false);
      case "settings" -> setting(id, field, before, after, list, context);
      case "prompts" -> prompt(field, entryBefore, entryAfter);
      case "dataFlows" -> dataFlow(field);
      case "aiFunctions" -> aiFunction(field);
      default -> undecided(List.of(), "Angabe ohne Regel.", false, false);
    };
  }

  private static Assessment dataFact(String field, Object before, Object after, ListChange list) {
    String name = field.contains("].") ? field.substring(field.indexOf("].") + 2) : field;
    if (name.startsWith("person.") || name.equals("personExcluded")) {
      return undecided(
          List.of(),
          "Die Zuordnung zur Person für die Selbstauskunft ist neu beschrieben; ob die Auskunft"
              + " mehr oder weniger umfasst, lässt sich daraus nicht ablesen.",
          false,
          false);
    }
    return switch (name) {
      case "personalReference" -> {
        int from = PERSONAL_REFERENCE_RANK.indexOf(String.valueOf(before));
        int to = PERSONAL_REFERENCE_RANK.indexOf(String.valueOf(after));
        if (from >= 0 && to >= 0 && to < from) {
          yield anzeige("Der Personenbezug wird schwächer.", true);
        }
        yield zustimmung("Z1", "Der Personenbezug wird stärker.", true, false);
      }
      case "personalColumns" ->
          list.added().isEmpty()
              ? anzeige("Entfallene personenbezogene Felder.", true)
              : zustimmung("Z1", "Neue personenbezogene Felder.", true, false);
      case "nonPersonalColumns" ->
          list.removed().isEmpty()
              ? bagatelle("Neue Spalte ohne Personenbezug.")
              : anzeige("Entfallene Felder ohne Personenbezug.", false);
      case "recordFields" ->
          list.added().isEmpty()
              ? anzeige("Entfallene Felder des Protokollsatzes.", true)
              : zustimmung("Z1", "Neue Felder im Protokollsatz.", true, false);
      case "visibility", "readers" -> {
        if (list.added().isEmpty()) {
          yield anzeige("Die Sichtbarkeit wird eingeschränkt.", true);
        }
        if (!list.removed().isEmpty() && FREE_TEXT_LISTS.contains(name)) {
          yield undecided(
              List.of("Z2"),
              "Wer die Daten sieht, ist neu beschrieben; ob jemand mehr sieht oder nur umformuliert"
                  + " wurde, lässt sich aus dem Text nicht ablesen.",
              true,
              false);
        }
        yield zustimmung(
            "Z2", "Neue Sichtbarkeit: Weitere Rollen oder Personen sehen die Daten.", true, false);
      }
      case "accessPaths" -> {
        if (list.added().isEmpty()) {
          yield anzeige("Ein Zugriffsweg entfällt.", false);
        }
        if (list.added().contains("EXPORT")) {
          yield list.added().size() > 1
              ? zustimmung(
                  List.of("Z2", "Z3"),
                  "Neuer Export und weiterer Zugriffsweg: Die Daten werden auf weiterem Weg sichtbar.",
                  true,
                  false)
              : zustimmung("Z3", "Neuer Export.", true, false);
        }
        yield zustimmung(
            "Z2", "Neuer Zugriffsweg: Die Daten werden auf weiterem Weg sichtbar.", false, false);
      }
      case "evaluations" ->
          undecided(
              List.of("Z3"),
              "Die möglichen Auswertungen sind neu beschrieben; ob eine neue hinzukommt, lässt sich"
                  + " aus dem Text nicht ablesen.",
              false,
              false);
      case "retention.rule", "retention.settings" ->
          undecided(
              List.of("Z4"),
              "Die Frist ist neu beschrieben; ob sie länger oder kürzer wird, lässt sich daraus"
                  + " nicht ablesen.",
              true,
              false);
      case "purpose" -> undecided(List.of(), "Der Zweck ist neu beschrieben.", true, false);
      case "deletion" -> undecided(List.of(), "Der Löschweg ist neu beschrieben.", true, false);
      default -> undecided(List.of(), "Geänderte Beschreibung.", false, false);
    };
  }

  private static Assessment setting(
      String key, String field, Object before, Object after, ListChange list, Context context) {
    boolean retention = context.isRetention(key);
    if (field.equals("defaultValue") || field.equals("helm.defaultValue")) {
      if (retention) {
        Long from =
            before == null ? null : DurationReading.approximateSeconds(String.valueOf(before), key);
        Long to =
            after == null ? null : DurationReading.approximateSeconds(String.valueOf(after), key);
        if (from != null && from.equals(to)) {
          return bagatelle("Gleiche Länge der Frist in anderer Schreibweise.");
        }
        if (from != null && to != null) {
          return to > from
              ? zustimmung("Z4", "Längere Frist.", true, false)
              : anzeige("Kürzere Frist.", true);
        }
        return undecided(
            List.of("Z4"),
            "Geänderte Frist, deren Länge sich nicht vergleichen lässt.",
            true,
            false);
      }
      if (isAddress(key)) {
        return zustimmung(
            List.of("Z5", "Z7"), "Neues voreingestelltes Ziel, an das Daten gehen.", true, true);
      }
      if (isModel(key)) {
        return anzeige(
            "Geändertes Standardmodell; die Adresse, an die Daten gehen, ist eine eigene"
                + " Voreinstellung.",
            false);
      }
      return zustimmung("Z7", "Geänderte Voreinstellung mit Datenwirkung.", false, false);
    }
    return undecided(
        List.of(retention ? "Z4" : "Z7"),
        list.isList()
            ? "Geänderte Angabe zur Voreinstellung."
            : "Geänderte Beschreibung der Voreinstellung.",
        retention,
        false);
  }

  private static Assessment prompt(
      String field, Map<String, Object> entryBefore, Map<String, Object> entryAfter) {
    boolean sameText = Objects.equals(entryBefore.get("sha256"), entryAfter.get("sha256"));
    return switch (field) {
      case "sha256" ->
          anzeige(
              "Geänderter System-Prompt (Prüfsumme gewechselt): Das Verhalten des Modells ändert"
                  + " sich. Ob er eine neue Auswertung bewirkt, prüft die Behörde.",
              false);
      case "classes", "databaseDefault" ->
          sameText
              ? bagatelle("Nur die Fundstelle im Code ändert sich, der Prompt-Text nicht.")
              : anzeige("Fundstelle eines geänderten System-Prompts.", false);
      case "purpose" ->
          undecided(List.of("Z3"), "Der Zweck des Prompts ist neu beschrieben.", false, false);
      default -> undecided(List.of(), "Geänderte Beschreibung.", false, false);
    };
  }

  private static Assessment dataFlow(String field) {
    return switch (field) {
      case "target" -> zustimmung("Z5", "Neues Ziel eines bestehenden Datenflusses.", true, true);
      case "group" ->
          undecided(
              List.of("Z5"),
              "Die Art des Gegenübers (Gruppe im Übersichtsbild) ändert sich; ob damit ein anderes"
                  + " Ziel gemeint ist, lässt sich aus dem Manifest nicht ablesen.",
              false,
              false);
      case "data" ->
          undecided(
              List.of("Z5"),
              "Die übermittelten Daten sind neu beschrieben; ob mehr übermittelt wird, lässt sich"
                  + " aus dem Text nicht ablesen.",
              true,
              false);
      case "viaOutboundProxy" ->
          undecided(
              List.of("Z5"),
              "Der Weg der Verbindung über den Ausgangsproxy ändert sich.",
              false,
              true);
      default -> undecided(List.of("Z5"), "Geänderte Angabe zum Datenfluss.", false, false);
    };
  }

  private static Assessment aiFunction(String field) {
    return switch (field) {
      case "outputKind", "interactsWithPersons", "assessesPersons", "labelling", "prompts" ->
          zustimmung("Z6", "Geänderte Angabe zur Einstufung nach KI-Verordnung.", false, false);
      case "purpose" ->
          undecided(List.of("Z6"), "Der Zweck der KI-Funktion ist neu beschrieben.", false, false);
      default -> undecided(List.of(), "Geänderte Beschreibung.", false, false);
    };
  }

  private static boolean personal(Map<String, Object> entry) {
    if (!"NONE".equals(entry.get("personalReference"))) {
      return true;
    }
    return entry.entrySet().stream()
        .anyMatch(
            e ->
                e.getKey().endsWith(".personalColumns")
                    && e.getValue() instanceof List<?> columns
                    && !columns.isEmpty());
  }

  private static boolean hasTable(Map<String, Object> entry) {
    return entry.keySet().stream().anyMatch(field -> field.startsWith("tables["));
  }

  private static String lastSegment(String key) {
    String lower = key.toLowerCase(Locale.ROOT);
    return lower.substring(Math.max(lower.lastIndexOf('.'), lower.lastIndexOf('#')) + 1);
  }

  private static String normalized(String text) {
    return WHITESPACE.matcher(text.strip()).replaceAll(" ");
  }

  private static Assessment zustimmung(
      String zClass, String reason, boolean vvt, boolean security) {
    return zustimmung(List.of(zClass), reason, vvt, security);
  }

  private static Assessment zustimmung(
      List<String> classes, String reason, boolean vvt, boolean security) {
    return new Assessment(Stage.ZUSTIMMUNG, classes, reason, false, vvt, true, security);
  }

  private static Assessment undecided(
      List<String> classes, String reason, boolean vvt, boolean security) {
    return new Assessment(
        Stage.ZUSTIMMUNG,
        classes,
        reason + " Die Zuordnung ist offen; im Zweifel Zustimmung.",
        true,
        vvt,
        true,
        security);
  }

  private static Assessment anzeige(String reason, boolean vvt) {
    return new Assessment(Stage.ANZEIGE, List.of(), reason, false, vvt, false, false);
  }

  private static Assessment bagatelle(String reason) {
    return new Assessment(Stage.BAGATELLE, List.of(), reason, false, false, false, false);
  }

  /** Items of a list field only one side has; both empty for a scalar field. */
  private record ListChange(boolean isList, List<String> added, List<String> removed) {

    static ListChange of(Object before, Object after) {
      if (!(before instanceof List<?>) && !(after instanceof List<?>)) {
        return new ListChange(false, List.of(), List.of());
      }
      List<String> from = strings(before);
      List<String> to = strings(after);
      List<String> added = new ArrayList<>(to);
      added.removeAll(from);
      List<String> removed = new ArrayList<>(from);
      removed.removeAll(to);
      return new ListChange(true, added, removed);
    }

    private static List<String> strings(Object value) {
      return value instanceof List<?> list
          ? list.stream().map(String::valueOf).toList()
          : List.of();
    }
  }
}
