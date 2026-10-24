package io.opaa.compliance;

import io.opaa.compliance.InstanceMatch.Entry;
import io.opaa.compliance.InstanceMatch.Export;
import io.opaa.compliance.InstanceMatch.OwnChange;
import io.opaa.compliance.InstanceMatch.Relevance;
import io.opaa.compliance.StageRules.Assessment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders an {@link InstanceMatch} as Markdown for the staff council and the data protection
 * officer and as JSON for tools. Neither carries a generation date, so the same files give the same
 * output; every cell passes {@link FactSheetRenderer#text(String)} or {@link
 * FactSheetRenderer#code(String)}.
 */
public final class InstanceMatchRenderer {

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(MapperFeature.INFER_RECORD_GETTERS_FROM_COMPONENTS_ONLY)
          .build();

  private InstanceMatchRenderer() {}

  /** The match as one Markdown document. */
  public static String markdown(InstanceMatch match) {
    StringBuilder out = new StringBuilder();
    out.append("---\nerzeugung: generiert\n")
        .append("delta-von: ")
        .append(match.delta().fromVersion())
        .append("\ndelta-bis: ")
        .append(match.delta().toVersion())
        .append("\nexport-version: ")
        .append(match.export().version())
        .append("\nexport-stand: ")
        .append(match.export().generatedAt())
        .append("\n---\n\n");
    out.append("# Abgleich des Update-Deltas mit der Instanz\n\n");
    out.append("> ").append(FactSheetRenderer.NOTICE).append(' ');
    out.append(ManifestDeltaRenderer.PROPOSAL_NOTICE).append("\n\n");
    out.append("| Angabe | Wert |\n|---|---|\n");
    row(
        out,
        "Update",
        FactSheetRenderer.text(match.delta().fromVersion())
            + " → "
            + FactSheetRenderer.text(match.delta().toVersion()));
    row(out, "Konfigurationsexport", export(match.export()));
    row(
        out,
        "Früherer Export",
        match.previousExport() == null ? "keiner angegeben" : export(match.previousExport()));
    out.append('\n');
    out.append(
        "Je Änderung des Updates steht, ob sie diese Instanz betrifft. Nicht betroffen ist eine"
            + " geänderte Voreinstellung, die die Instanz überschrieben hat, und ein Datenfluss,"
            + " den sie nicht eingeschaltet hat. Im Zweifel gilt eine Änderung als betroffen."
            + " Stufenvorschläge stammen aus dem Delta und gelten unverändert; „betrifft sie"
            + " nicht“ heißt nur, dass die Änderung in dieser Konfiguration nicht wirkt.\n\n");
    for (String note : match.notes()) {
      out.append("> **Hinweis:** ").append(note).append("\n\n");
    }
    overview(out, match);
    entries(out, match.entries());
    own(out, match);
    return out.toString();
  }

  /** The match as JSON, every change with its relevance and every own change with its proposal. */
  public static String json(InstanceMatch match) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("erzeugung", "generiert");
    root.put("notice", FactSheetRenderer.NOTICE);
    root.put("proposalNotice", ManifestDeltaRenderer.PROPOSAL_NOTICE);
    root.put("delta", match.delta());
    root.put("export", match.export());
    root.put("previousExport", match.previousExport());
    root.put("notes", match.notes());
    List<Map<String, Object>> entries = new ArrayList<>();
    for (Entry entry : match.entries()) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("group", entry.group());
      value.put("section", entry.section());
      value.put("id", entry.id());
      value.put("title", entry.title());
      value.put("kind", entry.kind());
      value.put("field", entry.field());
      value.put("before", entry.before());
      value.put("after", entry.after());
      value.put("assessment", entry.assessment());
      value.put("relevance", entry.relevance());
      value.put("relevanceTitle", entry.relevance().title());
      value.put("reason", entry.reason());
      entries.add(value);
    }
    root.put("changes", entries);
    root.put("ownChanges", match.ownChanges());
    return JSON.writeValueAsString(root) + "\n";
  }

  private static void overview(StringBuilder out, InstanceMatch match) {
    out.append("## Übersicht\n\n| Einordnung | Änderungen |\n|---|---|\n");
    for (Relevance relevance : Relevance.values()) {
      long count = match.entries().stream().filter(e -> e.relevance() == relevance).count();
      row(out, relevance.title(), Long.toString(count));
    }
    row(
        out,
        "Änderungen der Dienststelle",
        match.previousExport() == null
            ? "nicht verglichen"
            : Long.toString(match.ownChanges().size()));
    out.append('\n');
  }

  private static void entries(StringBuilder out, List<Entry> entries) {
    out.append("## Änderungen des Updates\n\n");
    if (entries.isEmpty()) {
      out.append("Das Delta enthält keine Änderung.\n\n");
      return;
    }
    out.append("| Eintrag | Änderung | Vorschlag | Instanz | Begründung |\n");
    out.append("|---|---|---|---|---|\n");
    for (Entry entry : entries) {
      row(
          out,
          FactSheetRenderer.code(entry.id())
              + (entry.title().isEmpty() || entry.title().equals(entry.id())
                  ? ""
                  : " " + FactSheetRenderer.text(entry.title())),
          change(entry),
          proposal(entry.assessment()),
          "**" + entry.relevance().title() + "**",
          FactSheetRenderer.text(entry.reason()));
    }
    out.append('\n');
  }

  private static void own(StringBuilder out, InstanceMatch match) {
    out.append("## Änderungen der Dienststelle\n\n");
    if (match.previousExport() == null) {
      out.append(
          "Kein früherer Export angegeben. Mit einem zweiten Export derselben Instanz zeigt dieser"
              + " Abschnitt, was die Dienststelle selbst geändert hat, etwa eine verlängerte Frist"
              + " oder einen eingeschalteten Konnektor.\n");
      return;
    }
    out.append(
        "Was sich zwischen beiden Exporten in der Konfiguration der Instanz geändert hat, mit"
            + " denselben Stufenregeln wie das Delta.\n\n");
    if (match.ownChanges().isEmpty()) {
      out.append("Keine Änderung der Dienststelle zwischen beiden Exporten.\n");
      return;
    }
    out.append("| Eintrag | Änderung | Vorher | Nachher | Vorschlag | Begründung |\n");
    out.append("|---|---|---|---|---|---|\n");
    for (OwnChange change : match.ownChanges()) {
      row(
          out,
          FactSheetRenderer.code(change.id()),
          FactSheetRenderer.text(change.change()),
          shown(change.before()),
          shown(change.after()),
          proposal(change.assessment()),
          FactSheetRenderer.text(change.assessment().reason()));
    }
  }

  private static String export(Export export) {
    return "Version "
        + FactSheetRenderer.code(export.version())
        + ", Commit "
        + FactSheetRenderer.code(export.commit())
        + ", Stand "
        + FactSheetRenderer.text(export.generatedAt())
        + " ("
        + FactSheetRenderer.code(export.source())
        + ")";
  }

  private static String change(Entry entry) {
    return switch (entry.kind()) {
      case "ADDED" -> "neu";
      case "REMOVED" -> "entfällt";
      default ->
          FactSheetRenderer.code(entry.field() == null ? "" : entry.field())
              + ": "
              + value(entry.before())
              + " → "
              + value(entry.after());
    };
  }

  private static String value(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "–";
    }
    return shown(node.isString() ? node.asString() : node.toString());
  }

  private static String shown(String value) {
    if (value == null || value.isEmpty()) {
      return "–";
    }
    String line = value.replaceAll("\\s*\\R\\s*", " ").strip();
    return line.length() > 80
        ? FactSheetRenderer.code(line.substring(0, 79) + "…")
        : FactSheetRenderer.code(line);
  }

  private static String proposal(JsonNode assessment) {
    String stage = InstanceMatch.text(assessment.path("stage"));
    List<String> qualifiers = new ArrayList<>();
    assessment.path("classes").forEach(c -> qualifiers.add(c.asString()));
    if (assessment.path("undecided").asBoolean(false)) {
      qualifiers.add("Zuordnung offen");
    }
    return title(stage) + (qualifiers.isEmpty() ? "" : " (" + String.join(", ", qualifiers) + ")");
  }

  private static String proposal(Assessment assessment) {
    List<String> qualifiers = new ArrayList<>(assessment.classes());
    if (assessment.undecided()) {
      qualifiers.add("Zuordnung offen");
    }
    return assessment.stage().title()
        + (qualifiers.isEmpty() ? "" : " (" + String.join(", ", qualifiers) + ")");
  }

  private static String title(String stage) {
    for (StageRules.Stage known : StageRules.Stage.values()) {
      if (known.name().equals(stage)) {
        return known.title();
      }
    }
    return stage.isEmpty() ? "–" : FactSheetRenderer.text(stage);
  }

  private static void row(StringBuilder out, String... cells) {
    out.append("| ").append(String.join(" | ", cells)).append(" |\n");
  }
}
