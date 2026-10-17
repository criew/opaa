package io.opaa.compliance;

import io.opaa.compliance.ManifestDelta.Change;
import io.opaa.compliance.ManifestDelta.Group;
import io.opaa.compliance.ManifestDelta.Kind;
import io.opaa.compliance.ManifestSnapshot.Origin;
import io.opaa.compliance.StageRules.Assessment;
import io.opaa.compliance.StageRules.Stage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders a {@link ManifestDelta} as Markdown for the authority, in plain German, and as JSON for
 * tools such as the release workflow and the instance comparison. Both carry no generation date, so
 * the same two manifests always give the same files.
 */
public final class ManifestDeltaRenderer {

  /** The notice under the title, stating that stages and references are proposals. */
  public static final String PROPOSAL_NOTICE =
      "Stufen und Bezüge sind Vorschläge; die Bewertung trifft die Behörde.";

  private static final String REPOSITORY_FILES = "https://github.com/criew/opaa/blob/";
  private static final String STAGE_RULES_PATH =
      "/docs/features/behoerdenunterlagen.md#die-stufenregeln";
  private static final Pattern COMMIT_ID = Pattern.compile("[0-9a-f]{7,64}");
  private static final int SHORT_COMMIT = 7;
  private static final String NONE = "—";

  /** From this many entries on, one identical change is shown once with the entries it concerns. */
  private static final int SHARED_AT_LEAST = 3;

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(MapperFeature.INFER_RECORD_GETTERS_FROM_COMPONENTS_ONLY)
          .build();

  private static final Map<String, String> FIELD_LABELS = fieldLabels();
  private static final Set<String> CODE_FIELDS =
      Set.of(
          "columns",
          "classes",
          "databaseDefault",
          "sha256",
          "settings",
          "retention.settings",
          "prompts",
          "defaultValue",
          "helm.values",
          "helm.defaultValue",
          "area");
  private static final Set<String> BOOLEAN_FIELDS =
      Set.of("viaOutboundProxy", "interactsWithPersons", "assessesPersons");

  private ManifestDeltaRenderer() {}

  /**
   * Pull requests labelled {@code data-impact} between the two versions.
   *
   * @param queried a list was given at all
   * @param filteredByRange the list was narrowed to the commits between both versions
   */
  public record LabelledChanges(
      boolean queried, boolean filteredByRange, List<PullRequest> pullRequests) {

    public LabelledChanges {
      pullRequests = List.copyOf(pullRequests);
    }

    /** No list was given. */
    public static LabelledChanges notQueried() {
      return new LabelledChanges(false, false, List.of());
    }
  }

  /** A labelled pull request; {@code url} may be empty. */
  public record PullRequest(int number, String title, String url) {}

  /** The Markdown document. */
  public static String markdown(ManifestDelta delta, LabelledChanges labelled) {
    StringBuilder out = new StringBuilder();
    Origin from = delta.from();
    Origin to = delta.to();
    out.append("---\n")
        .append("erzeugung: generiert\n")
        .append("von: ")
        .append(from.version())
        .append("\nvon-commit: ")
        .append(from.commit())
        .append("\nbis: ")
        .append(to.version())
        .append("\nbis-commit: ")
        .append(to.commit())
        .append("\n---\n\n")
        .append("# Änderungen der Fakten von ")
        .append(name(from))
        .append(" bis ")
        .append(name(to))
        .append("\n\n> ")
        .append(FactSheetRenderer.NOTICE)
        .append("\n> ")
        .append(PROPOSAL_NOTICE)
        .append("\n\n")
        .append("Dieses Delta vergleicht ")
        .append(
            bareCommit(from)
                ? "den Stand ohne Fakten-Manifest (Commit "
                    + FactSheetRenderer.code(from.commit())
                    + ")"
                : "das Fakten-Manifest von OPAA " + version(from))
        .append(" direkt mit ")
        .append(
            bareCommit(to)
                ? "dem Stand ohne Fakten-Manifest (Commit "
                    + FactSheetRenderer.code(to.commit())
                    + ")"
                : (bareCommit(from) ? "dem Fakten-Manifest von OPAA " : "dem von OPAA ")
                    + version(to))
        .append(". Was zwischen beiden Versionen hinzukam und wieder entfiel, fehlt; ein mehrfach")
        .append(" geänderter Wert erscheint einmal mit Anfangs- und Endstand. Jede Änderung trägt")
        .append(" einen Stufenvorschlag nach den [Stufenregeln](")
        .append(stageRules(to))
        .append(") (Bagatelle, Anzeige, Zustimmung mit den Klassen Z1 bis Z7; im Zweifel")
        .append(" Zustimmung) und den Bezug zu Verzeichnis der Verarbeitungstätigkeiten (VVT),")
        .append(" Datenschutz-Folgenabschätzung (DSFA) und Sicherheitskonzept. Die Überprüfung der")
        .append(" DSFA schlägt das Delta bei jeder Änderung der Stufe Zustimmung vor; das ist eine")
        .append(" Faustregel, keine Ableitung aus Art. 35 Abs. 11 DSGVO. Neue")
        .append(" Konfigurationsschlüssel ohne Datenwirkung stehen nicht im Manifest und fehlen")
        .append(" hier.\n\n");
    for (Origin origin : List.of(from, to)) {
      if (!origin.hasManifest()) {
        out.append(
                bareCommit(origin)
                    ? "> **Hinweis:** Der Stand (Commit " + shortCommit(origin) + ")"
                    : "> **Hinweis:** OPAA " + FactSheetRenderer.text(origin.version()))
            .append(" hat noch kein Fakten-Manifest. ")
            .append(
                origin == from
                    ? "Jeder Eintrag erscheint deshalb als neu."
                    : "Jeder Eintrag erscheint deshalb als entfallen.")
            .append("\n\n");
      }
    }
    overview(out, delta);
    Map<Group, List<Change>> byGroup = new EnumMap<>(Group.class);
    delta.changes().forEach(c -> byGroup.computeIfAbsent(c.group(), g -> new ArrayList<>()).add(c));
    for (Group group : Group.values()) {
      List<Change> changes = byGroup.getOrDefault(group, List.of());
      if (changes.isEmpty() && (group == Group.STRUCTURE || group == Group.OTHER)) {
        continue;
      }
      out.append("## ").append(group.title()).append("\n\n");
      if (changes.isEmpty()) {
        out.append("Keine Änderungen.\n\n");
        continue;
      }
      Map<List<Object>, List<Change>> alike = new LinkedHashMap<>();
      changes.stream()
          .filter(change -> change.kind() == Kind.CHANGED)
          .forEach(
              change ->
                  alike.computeIfAbsent(sameChange(change), k -> new ArrayList<>()).add(change));
      alike.values().removeIf(shared -> shared.size() < SHARED_AT_LEAST);
      for (List<Change> shared : alike.values()) {
        out.append("### Gleiche Änderung an ")
            .append(shared.size())
            .append(" Einträgen\n\nBetrifft: ")
            .append(
                shared.stream()
                    .map(c -> FactSheetRenderer.code(c.id()))
                    .collect(Collectors.joining(", ")))
            .append("\n\n");
        change(out, shared.getFirst());
      }
      String entry = null;
      for (Change change : changes) {
        if (change.kind() == Kind.CHANGED && alike.containsKey(sameChange(change))) {
          continue;
        }
        String key = change.section() + "\u0000" + change.id();
        if (!key.equals(entry)) {
          entry = key;
          out.append("### ").append(heading(change)).append("\n\n");
        }
        change(out, change);
      }
    }
    labelled(out, labelled);
    return out.toString().stripTrailing() + "\n";
  }

  /** The JSON document. */
  public static String json(ManifestDelta delta, LabelledChanges labelled) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("erzeugung", "generiert");
    root.put("notice", FactSheetRenderer.NOTICE);
    root.put("proposalNotice", PROPOSAL_NOTICE);
    root.put("stageRules", stageRules(delta.to()));
    root.put("from", delta.from());
    root.put("to", delta.to());
    root.put("summary", summary(delta));
    root.put("changes", delta.changes());
    root.put("labelledPullRequests", labelled);
    return JSON.writeValueAsString(root) + "\n";
  }

  private static Map<String, Object> summary(ManifestDelta delta) {
    Map<String, Object> summary = new LinkedHashMap<>();
    Map<Stage, Long> stages = new EnumMap<>(Stage.class);
    for (Stage stage : Stage.values()) {
      stages.put(stage, delta.changes().stream().filter(c -> stage(c) == stage).count());
    }
    summary.put("stages", stages);
    summary.put("vvt", delta.changes().stream().filter(c -> c.assessment().vvt()).count());
    summary.put("dsfa", delta.changes().stream().filter(c -> c.assessment().dsfa()).count());
    summary.put(
        "securityConcept",
        delta.changes().stream().filter(c -> c.assessment().securityConcept()).count());
    summary.put(
        "undecided", delta.changes().stream().filter(c -> c.assessment().undecided()).count());
    summary.put("classes", classes(delta));
    return summary;
  }

  private static void overview(StringBuilder out, ManifestDelta delta) {
    @SuppressWarnings("unchecked")
    Map<Stage, Long> stages = (Map<Stage, Long>) summary(delta).get("stages");
    out.append("## Übersicht\n\n").append("| Stufenvorschlag | Änderungen |\n|---|---|\n");
    for (Stage stage : List.of(Stage.ZUSTIMMUNG, Stage.ANZEIGE, Stage.BAGATELLE)) {
      out.append("| ").append(stage.title()).append(" | ").append(stages.get(stage)).append(" |\n");
    }
    List<String> classes = classes(delta);
    long undecided = delta.changes().stream().filter(c -> c.assessment().undecided()).count();
    out.append('\n')
        .append(
            "Gezählt ist jede Änderung je Eintrag; dieselbe Änderung an mehreren Einträgen steht")
        .append(" unten einmal mit allen betroffenen Einträgen.\n\n")
        .append("- Erfüllte Zustimmungsklassen: ")
        .append(classes.isEmpty() ? "keine" : String.join(", ", classes))
        .append('\n')
        .append("- Davon Zustimmung, weil die Zuordnung offen ist: ")
        .append(undecided)
        .append('\n')
        .append("- VVT anzupassen: ")
        .append(delta.changes().stream().filter(c -> c.assessment().vvt()).count())
        .append(" Änderungen\n")
        .append("- DSFA zu überprüfen: ")
        .append(delta.changes().stream().filter(c -> c.assessment().dsfa()).count())
        .append(" Änderungen\n")
        .append("- Sicherheitskonzept fortzuschreiben: ")
        .append(delta.changes().stream().filter(c -> c.assessment().securityConcept()).count())
        .append(" Änderungen\n\n");
  }

  private static void change(StringBuilder out, Change change) {
    Assessment assessment = change.assessment();
    out.append("- **")
        .append(
            switch (change.kind()) {
              case ADDED -> "Neu";
              case REMOVED -> "Entfällt";
              case CHANGED -> label(change.field()) + " geändert";
            })
        .append("**: Vorschlag **")
        .append(assessment.stage().title())
        .append("**");
    List<String> qualifiers = new ArrayList<>(assessment.classes());
    if (assessment.undecided()) {
      qualifiers.add("Zuordnung offen");
    }
    if (!qualifiers.isEmpty()) {
      out.append(" (").append(String.join(", ", qualifiers)).append(')');
    }
    out.append('\n');
    switch (change.kind()) {
      case ADDED -> entry(out, "Angaben", change.after());
      case REMOVED -> entry(out, "Bisher", change.before());
      case CHANGED -> {
        if (change.before() instanceof List<?> || change.after() instanceof List<?>) {
          List<String> before = strings(change.before());
          List<String> after = strings(change.after());
          List<String> added = after.stream().filter(v -> !before.contains(v)).toList();
          List<String> removed = before.stream().filter(v -> !after.contains(v)).toList();
          if (!added.isEmpty()) {
            item(out, "  ", "Hinzu", change.field(), added);
          }
          if (!removed.isEmpty()) {
            item(out, "  ", "Entfällt", change.field(), removed);
          }
          if (added.isEmpty() && removed.isEmpty()) {
            item(out, "  ", "Vorher", change.field(), change.before());
            item(out, "  ", "Nachher", change.field(), change.after());
          }
        } else {
          item(out, "  ", "Vorher", change.field(), change.before());
          item(out, "  ", "Nachher", change.field(), change.after());
        }
      }
    }
    out.append("  - Begründung: ").append(FactSheetRenderer.text(assessment.reason())).append('\n');
    out.append("  - VVT anpassen: ")
        .append(yesNo(assessment.vvt()))
        .append(" · DSFA überprüfen: ")
        .append(yesNo(assessment.dsfa()))
        .append(" · Sicherheitskonzept fortschreiben: ")
        .append(yesNo(assessment.securityConcept()))
        .append("\n\n");
  }

  private static void labelled(StringBuilder out, LabelledChanges labelled) {
    out.append("## Weitere gekennzeichnete Änderungen\n\n");
    if (!labelled.queried()) {
      out.append(
          "Nicht abgefragt: Diesem Lauf lag keine Liste der Pull Requests mit dem Label"
              + " `data-impact` vor. Die Release-Notizen führen sie unter „Datenrelevante"
              + " Änderungen“.\n");
      return;
    }
    out.append(
        "Pull Requests mit dem Label `data-impact`. Ihre Wirkung steht nicht immer im Manifest,"
            + " etwa bei einer neuen Auswertung in der Oberfläche; den Satz zur Datenwirkung nennt"
            + " die Beschreibung des Pull Requests.");
    if (!labelled.filteredByRange()) {
      out.append(
          " Die Liste ließ sich nicht auf die Commits zwischen beiden Versionen eingrenzen und"
              + " enthält alle übergebenen Pull Requests.");
    }
    out.append("\n\n");
    if (labelled.pullRequests().isEmpty()) {
      out.append("Keine.\n");
      return;
    }
    for (PullRequest pr : labelled.pullRequests()) {
      String label = "#" + pr.number() + " " + FactSheetRenderer.text(pr.title());
      out.append("- ")
          .append(
              pr.url() == null || pr.url().isBlank() ? label : "[" + label + "](" + pr.url() + ")")
          .append('\n');
    }
  }

  /** What makes two changes of different entries the same change. */
  private static List<Object> sameChange(Change change) {
    return Arrays.asList(
        change.section(), change.field(), change.before(), change.after(), change.assessment());
  }

  private static String heading(Change change) {
    String noun =
        switch (change.section()) {
          case "data" -> "Datum";
          case "nonPersonalTables" -> "Tabelle ohne Personenbezug";
          case "auditEvents" -> "Protokollereignis";
          case "roles" -> "Rolle";
          case "capabilities" -> "Fähigkeit";
          case "settings" -> change.group() == Group.RETENTION ? "Frist" : "Voreinstellung";
          case "prompts" -> "System-Prompt";
          case "dataFlows" -> "Datenfluss";
          case "aiFunctions" -> "KI-Funktion";
          case ManifestDelta.AREAS -> "Bereich";
          default -> "Eintrag in " + FactSheetRenderer.code(change.section());
        };
    String id = FactSheetRenderer.code(change.id());
    return change.title().equals(change.id())
        ? noun + " " + id
        : noun + " „" + FactSheetRenderer.text(change.title()) + "“ (" + id + ")";
  }

  /** Every field of an added or removed entry, in the order of the labels, as sub-items. */
  private static void entry(StringBuilder out, String name, Object entry) {
    if (!(entry instanceof Map<?, ?> fields) || fields.isEmpty()) {
      out.append("  - ").append(name).append(": ").append(NONE).append('\n');
      return;
    }
    out.append("  - ").append(name).append(":\n");
    List<String> order = List.copyOf(FIELD_LABELS.keySet());
    fields.keySet().stream()
        .map(String::valueOf)
        .sorted(
            Comparator.comparingInt(
                    (String field) -> {
                      int rank = order.indexOf(baseName(field));
                      return rank < 0 ? order.size() : rank;
                    })
                .thenComparing(Comparator.naturalOrder()))
        .forEach(field -> item(out, "    ", label(field), field, fields.get(field)));
  }

  /**
   * One line {@code - name: value}; a list of more than one value continues as a nested list, one
   * value per line.
   */
  private static void item(
      StringBuilder out, String indent, String name, String field, Object value) {
    out.append(indent).append("- ").append(name).append(':');
    List<String> values = values(field, value);
    if (values.size() == 1) {
      out.append(' ').append(values.getFirst()).append('\n');
      return;
    }
    out.append('\n');
    values.forEach(v -> out.append(indent).append("  - ").append(v).append('\n'));
  }

  private static String baseName(String field) {
    return field.contains("].") ? field.substring(field.indexOf("].") + 2) : field;
  }

  /** The German label of a field path. */
  static String label(String field) {
    if (field.startsWith("tables[")) {
      String table = field.substring("tables[".length(), field.indexOf(']'));
      String rest = field.substring(field.indexOf("].") + 2);
      return "Tabelle "
          + FactSheetRenderer.code(table)
          + ": "
          + FIELD_LABELS.getOrDefault(rest, FactSheetRenderer.code(rest));
    }
    return FIELD_LABELS.getOrDefault(field, FactSheetRenderer.code(field));
  }

  /** The rendered values of a field: one for a scalar, one per item for a list. */
  private static List<String> values(String field, Object value) {
    if (value == null) {
      return List.of("nicht angegeben");
    }
    String name = baseName(field);
    if (value instanceof List<?> list) {
      return list.isEmpty()
          ? List.of("keine")
          : list.stream().map(item -> scalar(name, String.valueOf(item))).toList();
    }
    return List.of(scalar(name, String.valueOf(value)));
  }

  private static String scalar(String field, String value) {
    if (BOOLEAN_FIELDS.contains(field)) {
      return switch (value) {
        case "true" -> "ja";
        case "false" -> "nein";
        default -> FactSheetRenderer.text(value);
      };
    }
    String translated =
        switch (field) {
          case "personalReference" ->
              switch (value) {
                case "NONE" -> "kein Personenbezug";
                case "IDENTIFIABLE" -> "personenbeziehbar";
                case "EMPLOYEE" -> "Beschäftigtendaten";
                default -> null;
              };
          case "accessPaths" ->
              switch (value) {
                case "UI" -> "Oberfläche";
                case "API" -> "API";
                case "EXPORT" -> "Export";
                case "DATABASE" -> "Datenbank";
                case "STORAGE" -> "Ablage des Betriebs (Dateisystem, Objektspeicher, Log)";
                default -> null;
              };
          case "bearings" ->
              switch (value) {
                case "DATA" -> "Daten";
                case "VISIBILITY" -> "Sichtbarkeit";
                case "EVALUATION" -> "Auswertung";
                case "DATA_FLOW" -> "Datenfluss";
                default -> null;
              };
          case "source" ->
              switch (value) {
                case "PROPERTY" -> "Konfigurationseigenschaft";
                case "DATABASE" -> "Verwaltung (Datenbank)";
                case "CONSTANT" -> "Konstante im Code";
                default -> null;
              };
          case "outputKind" ->
              switch (value) {
                case "TEXT" -> "Text";
                case "AUDIO" -> "Audio";
                case "IMAGE" -> "Bild";
                default -> null;
              };
          case "group" ->
              switch (value) {
                case "MODEL" -> "Modelle";
                case "CONNECTOR" -> "Konnektoren und Zugänge";
                case "MAIL" -> "Mail";
                case "IDENTITY" -> "Identität";
                case "STORAGE" -> "Speicher";
                default -> null;
              };
          default -> null;
        };
    if (translated != null) {
      return translated;
    }
    if (CODE_FIELDS.contains(field) || field.endsWith("Columns")) {
      return value.isEmpty() ? "leer" : FactSheetRenderer.code(value);
    }
    return value.isBlank() ? "leer" : FactSheetRenderer.text(value);
  }

  private static List<String> strings(Object value) {
    return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
  }

  private static Stage stage(Change change) {
    return change.assessment().stage();
  }

  private static List<String> classes(ManifestDelta delta) {
    Set<String> classes = new TreeSet<>();
    delta.changes().forEach(c -> classes.addAll(c.assessment().classes()));
    return List.copyOf(classes);
  }

  /**
   * The stage rules as of the newer version's commit, the rules its proposals follow; on {@code
   * main} only when that commit is unknown.
   */
  private static String stageRules(Origin to) {
    String commit = to.commit().equals(ManifestSnapshot.UNKNOWN) ? "main" : to.commit();
    return REPOSITORY_FILES + commit + STAGE_RULES_PATH;
  }

  /**
   * A version named by a bare commit id whose tree has no manifest, such as the root commit a first
   * final release compares with; it is named as such, not by the id alone.
   */
  private static boolean bareCommit(Origin origin) {
    return !origin.hasManifest()
        && "ref".equals(origin.kind())
        && COMMIT_ID.matcher(origin.version()).matches()
        && origin.commit().startsWith(origin.version());
  }

  /** The version as named in the title. */
  private static String name(Origin origin) {
    return bareCommit(origin)
        ? "Stand ohne Fakten-Manifest (Commit " + shortCommit(origin) + ")"
        : FactSheetRenderer.text(origin.version());
  }

  private static String shortCommit(Origin origin) {
    return FactSheetRenderer.code(
        origin.commit().substring(0, Math.min(SHORT_COMMIT, origin.commit().length())));
  }

  private static String version(Origin origin) {
    return FactSheetRenderer.text(origin.version())
        + " (Commit "
        + FactSheetRenderer.code(origin.commit())
        + ")";
  }

  private static String yesNo(boolean value) {
    return value ? "ja" : "nein";
  }

  private static Map<String, String> fieldLabels() {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("title", "Bezeichnung");
    labels.put("area", "Bereich");
    labels.put("purpose", "Zweck");
    labels.put("personalReference", "Personenbezug");
    labels.put("personalColumns", "personenbezogene Spalten");
    labels.put("nonPersonalColumns", "Spalten ohne Personenbezug");
    labels.put("columns", "Spalten");
    labels.put("storage", "Speicherort");
    labels.put("retention.rule", "Frist");
    labels.put("retention.settings", "Einstellung der Frist");
    labels.put("visibility", "Sichtbarkeit");
    labels.put("accessPaths", "Zugriffsweg");
    labels.put("deletion", "Löschweg");
    labels.put("evaluations", "Mögliche Auswertungen");
    labels.put("notes", "Hinweise");
    labels.put("trigger", "Auslöser");
    labels.put("recordFields", "Felder des Protokollsatzes");
    labels.put("readers", "Leser und Zweck");
    labels.put("details", "Inhalt des Protokollsatzes");
    labels.put("seesAboutOthers", "Sieht über andere Personen");
    labels.put("source", "Quelle");
    labels.put("defaultValue", "Standardwert");
    labels.put("range", "Wertebereich");
    labels.put("effect", "Wirkung");
    labels.put("bearings", "Bezug");
    labels.put("helm.values", "Wert im Helm-Chart");
    labels.put("helm.defaultValue", "Voreinstellung im Helm-Chart");
    labels.put("classes", "Fundstelle im Code");
    labels.put("databaseDefault", "Fundstelle in der Datenbank");
    labels.put("sha256", "Prüfsumme des Prompts");
    labels.put("group", "Gruppe im Übersichtsbild");
    labels.put("viaOutboundProxy", "Über den Ausgangsproxy");
    labels.put("target", "Ziel");
    labels.put("data", "Übermittelte Daten");
    labels.put("switchOff", "Abschaltbarkeit");
    labels.put("settings", "Einstellungen");
    labels.put("outputKind", "Art der Ausgabe");
    labels.put("interactsWithPersons", "Direkte Interaktion mit Personen");
    labels.put("assessesPersons", "Bewertung oder Profiling von Personen");
    labels.put("labelling", "Kennzeichnung nach Art. 50 KI-VO");
    labels.put("prompts", "System-Prompts");
    return Collections.unmodifiableMap(labels);
  }
}
