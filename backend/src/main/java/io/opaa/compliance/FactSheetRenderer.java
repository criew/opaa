package io.opaa.compliance;

import io.opaa.compliance.ComplianceManifest.AccessPath;
import io.opaa.compliance.ComplianceManifest.AiFunctionEntry;
import io.opaa.compliance.ComplianceManifest.AuditEventEntry;
import io.opaa.compliance.ComplianceManifest.Bearing;
import io.opaa.compliance.ComplianceManifest.DataEntry;
import io.opaa.compliance.ComplianceManifest.DataFacts;
import io.opaa.compliance.ComplianceManifest.DataFlowEntry;
import io.opaa.compliance.ComplianceManifest.OutputKind;
import io.opaa.compliance.ComplianceManifest.PersonPath;
import io.opaa.compliance.ComplianceManifest.PersonalReference;
import io.opaa.compliance.ComplianceManifest.PromptEntry;
import io.opaa.compliance.ComplianceManifest.RoleEntry;
import io.opaa.compliance.ComplianceManifest.SettingEntry;
import io.opaa.compliance.ComplianceManifest.SettingSource;
import io.opaa.compliance.ComplianceManifest.TableRef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders the {@link FactSheet fact sheets} and their JSON form from a {@link ComplianceManifest}.
 * Every entry of the manifest appears in at least one sheet, under an anchor {@link
 * #anchor(String)} derives from its identifier (prefixed {@code prompt-}, {@code ki-funktion-} or
 * {@code bereich-} where kinds share a sheet). A section without entries says "Noch nicht erfasst"
 * instead of disappearing, so a missing fact never reads as "none".
 */
public final class FactSheetRenderer {

  /** The notice under the title of every sheet. */
  public static final String NOTICE =
      "Fakten aus dem Code; rechtliche Bewertung bleibt bei der Behörde.";

  private static final String NOT_YET_CAPTURED_HEADING = "Noch nicht erfasst";
  private static final String EMPTY_SECTION =
      "Noch nicht erfasst: Das Manifest enthält hierzu noch keine Einträge.";
  private static final String NONE = "—";
  private static final String NOT_NAMED = "nicht genannt";
  private static final String ISSUES = "https://github.com/criew/opaa/issues/";
  private static final String VIA_PROXY =
      "über den Ausgangsproxy, sofern einer eingetragen ist und das Ziel weder lokal ist noch"
          + " auf seiner Ausnahmeliste steht (Handbuch Betrieb, Abschnitt „Ausgangsproxy“)";

  /**
   * Facts the manifest has no entries for yet, although the sheet promises them; an item is removed
   * together with the issue that brings its entries into the manifest.
   */
  private static final Map<FactSheet, List<Pending>> NOT_YET_CAPTURED = notYetCaptured();

  private static final Pattern NOT_ANCHOR = Pattern.compile("[^a-z0-9]+");
  private static final Pattern ANCHOR = Pattern.compile("<a id=\"([a-z0-9-]+)\"></a>");

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(MapperFeature.INFER_RECORD_GETTERS_FROM_COMPONENTS_ONLY)
          .build();

  private FactSheetRenderer() {}

  /** Something a sheet promises that no manifest entry holds yet, and the issue that adds it. */
  public record Pending(String text, int issue) {}

  /** Every fact sheet, in the order of {@link FactSheet}. */
  public static Map<FactSheet, String> render(
      ComplianceManifest manifest, FactSheetProvenance provenance) {
    Map<FactSheet, String> sheets = new EnumMap<>(FactSheet.class);
    sheets.put(FactSheet.DATENKATALOG, dataCatalogue(manifest, provenance));
    sheets.put(FactSheet.PROTOKOLLEREIGNISSE, auditEvents(manifest, provenance));
    sheets.put(FactSheet.ROLLENSICHT, roleView(manifest, provenance));
    sheets.put(FactSheet.DATENFLUESSE, dataFlows(manifest, provenance));
    sheets.put(FactSheet.VOREINSTELLUNGEN, defaults(manifest, provenance));
    sheets.put(FactSheet.FRISTEN, deadlines(manifest, provenance));
    return sheets;
  }

  /**
   * The machine-readable form for tools and the version delta: provenance, sheet names, what is not
   * yet captured, and the manifest as it was read.
   */
  public static String renderJson(ComplianceManifest manifest, FactSheetProvenance provenance) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("erzeugung", "generiert");
    root.put("opaaVersion", provenance.version());
    root.put("commit", provenance.commit());
    root.put("generatedOn", provenance.generatedOn().toString());
    root.put("notice", NOTICE);
    root.put("factSheets", Arrays.stream(FactSheet.values()).map(FactSheet::sheetName).toList());
    Map<String, List<Pending>> pending = new LinkedHashMap<>();
    NOT_YET_CAPTURED.forEach((sheet, items) -> pending.put(sheet.sheetName(), items));
    root.put("notYetCaptured", pending);
    root.put("manifest", manifest);
    return JSON.writeValueAsString(root) + "\n";
  }

  /**
   * Every placeholder target the rendered sheets offer, one per line in the form of {@code
   * {{faktenblatt:<target>}}}: each sheet name, then {@code <name>#<anchor>} for each anchor in it,
   * sorted. Anchors do not depend on the provenance.
   */
  public static List<String> placeholderTargets(ComplianceManifest manifest) {
    FactSheetProvenance any = new FactSheetProvenance("0.0.0-dev", "-", LocalDate.EPOCH);
    List<String> targets = new ArrayList<>();
    render(manifest, any)
        .forEach(
            (sheet, content) -> {
              targets.add(sheet.sheetName());
              ANCHOR
                  .matcher(content)
                  .results()
                  .map(match -> match.group(1))
                  .sorted()
                  .distinct()
                  .forEach(anchor -> targets.add(sheet.sheetName() + "#" + anchor));
            });
    return targets;
  }

  /**
   * The anchor of an identifier: lower case, every run of other characters than letters and digits
   * a single hyphen ({@code ASSET_GRANT_GRANTED} becomes {@code asset-grant-granted}).
   */
  public static String anchor(String identifier) {
    String anchor = NOT_ANCHOR.matcher(identifier.toLowerCase(Locale.ROOT)).replaceAll("-");
    return anchor.replaceAll("^-+|-+$", "");
  }

  private static String dataCatalogue(ComplianceManifest manifest, FactSheetProvenance provenance) {
    Sheet sheet =
        new Sheet(
            FactSheet.DATENKATALOG,
            provenance,
            "Alle Daten, die OPAA speichert, je Bereich mit Zweck, Personenbezug, Speicherort, Frist,"
                + " Sichtbarkeit, Zugriffsweg, Löschmechanismus und möglichen Auswertungen sowie der"
                + " Zuordnung zur Person: über welche Spalten ein Eintrag einer Person zugeordnet"
                + " wird oder warum er nicht Teil der Selbstauskunft ist. Am Ende stehen die"
                + " Tabellen ohne Personenbezug.");
    Map<String, SettingEntry> settings = settingsByKey(manifest);

    sheet.heading(2, null, "Übersicht");
    sheet.table(
        List.of("Datum", "Kennung", "Bereich", "Personenbezug"),
        manifest.data().stream()
            .map(
                entry ->
                    List.of(
                        link(text(entry.title()), "#" + entry.id()),
                        code(entry.id()),
                        text(areaTitle(manifest, entry.area())),
                        personalReference(entry.personalReference())))
            .toList());

    byArea(manifest.data(), DataEntry::area)
        .forEach(
            (area, entries) -> {
              sheet.heading(2, "bereich-" + anchor(area), text(areaTitle(manifest, area)));
              for (DataEntry entry : entries) {
                sheet.heading(3, entry.id(), text(entry.title()));
                List<List<String>> rows = new ArrayList<>();
                rows.add(List.of("Kennung", code(entry.id())));
                rows.add(List.of("Zweck", text(entry.purpose())));
                rows.add(List.of("Personenbezug", personalReference(entry.personalReference())));
                rows.add(
                    List.of(
                        "Spalten mit Personenbezug",
                        columns(entry.tables(), TableRef::personalColumns)));
                rows.add(
                    List.of(
                        "Spalten ohne Personenbezug",
                        columns(entry.tables(), TableRef::nonPersonalColumns)));
                rows.add(List.of("Zuordnung zur Person", personPath(entry)));
                rows.addAll(factRows(entry, settings));
                sheet.table(List.of("Angabe", "Inhalt"), rows);
              }
            });

    sheet.heading(2, "tabellen-ohne-personenbezug", "Tabellen ohne Personenbezug");
    sheet.table(
        List.of("Tabelle", "Spalten", "Bereich"),
        manifest.nonPersonalTables().stream()
            .map(
                table ->
                    List.of(
                        code(table.table()),
                        table.columns() == null ? NONE : codes(table.columns()),
                        text(areaTitle(manifest, table.area()))))
            .toList());
    return sheet.toString();
  }

  private static String auditEvents(ComplianceManifest manifest, FactSheetProvenance provenance) {
    Sheet sheet =
        new Sheet(
            FactSheet.PROTOKOLLEREIGNISSE,
            provenance,
            "Jedes Ereignis des revisionssicheren Protokolls mit Auslöser, Feldern des"
                + " Protokollsatzes und den Personen, die es zu welchem Zweck lesen, dazu Frist,"
                + " Speicherort, Zugriffsweg und Löschmechanismus.");

    sheet.heading(2, null, "Übersicht");
    sheet.table(
        List.of("Ereignis", "Auslöser"),
        manifest.auditEvents().stream()
            .map(
                entry ->
                    List.of(
                        link(code(entry.id()), "#" + anchor(entry.id())), text(entry.trigger())))
            .toList());

    Map<AuditEventEntry, List<List<String>>> facts = new LinkedHashMap<>();
    Map<String, SettingEntry> settings = settingsByKey(manifest);
    manifest.auditEvents().forEach(entry -> facts.put(entry, eventFactRows(entry, settings)));
    Set<List<String>> shared = sharedRows(facts.values());
    if (!shared.isEmpty()) {
      sheet.heading(2, "gemeinsame-angaben", "Gemeinsame Angaben aller Ereignisse");
      sheet.paragraph(
          "Diese Angaben gelten für jedes Ereignis gleich und stehen deshalb nur hier; ein"
              + " Ereignis nennt darunter nur, was es zusätzlich oder abweichend hat.");
      sheet.table(List.of("Angabe", "Inhalt"), List.copyOf(shared));
    }

    byArea(manifest.auditEvents(), AuditEventEntry::area)
        .forEach(
            (area, entries) -> {
              sheet.heading(2, "bereich-" + anchor(area), text(areaTitle(manifest, area)));
              for (AuditEventEntry entry : entries) {
                sheet.heading(3, anchor(entry.id()), code(entry.id()));
                List<List<String>> rows = new ArrayList<>();
                rows.add(List.of("Auslöser", text(entry.trigger())));
                rows.add(List.of("Einzelheiten", orNone(entry.details())));
                rows.add(List.of(PAYLOAD_KEYS_LABEL, payloadKeys(entry)));
                facts.get(entry).stream().filter(row -> !shared.contains(row)).forEach(rows::add);
                sheet.table(List.of("Angabe", "Inhalt"), rows);
              }
            });
    return sheet.toString();
  }

  private static List<List<String>> eventFactRows(
      AuditEventEntry entry, Map<String, SettingEntry> settings) {
    List<List<String>> rows = new ArrayList<>();
    rows.add(List.of("Felder des Protokollsatzes", codes(entry.recordFields())));
    rows.add(List.of("Wer liest zu welchem Zweck", texts(entry.readers())));
    rows.add(List.of("Zweck", text(entry.purpose())));
    rows.add(List.of("Personenbezug", personalReference(entry.personalReference())));
    rows.addAll(factRows(entry, settings));
    return rows;
  }

  /** The rows every one of at least two entries has with the same content, in their order. */
  private static Set<List<String>> sharedRows(Collection<List<List<String>>> entries) {
    Set<List<String>> shared = new LinkedHashSet<>();
    if (entries.size() < 2) {
      return shared;
    }
    shared.addAll(entries.iterator().next());
    entries.forEach(rows -> shared.retainAll(Set.copyOf(rows)));
    return shared;
  }

  private static String roleView(ComplianceManifest manifest, FactSheetProvenance provenance) {
    Sheet sheet =
        new Sheet(
            FactSheet.ROLLENSICHT,
            provenance,
            "Was eine Systemrolle oder Fähigkeit über andere Personen sieht („was sieht wer über"
                + " mich“), und welche Rolle die Sichtbarkeit eines Datums nennt.");

    sheet.heading(2, "systemrollen", "Systemrollen");
    roleSections(sheet, manifest.roles());
    sheet.heading(2, "faehigkeiten", "Fähigkeiten");
    roleSections(sheet, manifest.capabilities());

    sheet.heading(2, "matrix", "Matrix Rolle × Datenkategorie");
    sheet.paragraph(
        "Eine Zelle zeigt die Angaben zur Sichtbarkeit eines Datums, die die Systemrolle beim Namen"
            + " nennen. Angaben ohne Rollennamen (die Person selbst, Mitglieder, Verwaltende eines"
            + " Assets, der Betrieb) stehen unter „Weitere Sichtbarkeit“. „nicht genannt“ heißt nur,"
            + " dass die Angaben zu diesem Datum die Rolle nicht nennen; was eine Rolle insgesamt"
            + " sieht, steht in ihrem Abschnitt oben.");
    if (manifest.roles().isEmpty()) {
      sheet.paragraph(EMPTY_SECTION);
    } else {
      List<String> allVisibility = new ArrayList<>();
      manifest.data().forEach(entry -> allVisibility.addAll(entry.visibility()));
      manifest.auditEvents().forEach(entry -> allVisibility.addAll(entry.visibility()));
      List<String> roleIds =
          manifest.roles().stream()
              .map(RoleEntry::id)
              .filter(id -> allVisibility.stream().anyMatch(v -> mentions(v, id)))
              .toList();
      List<String> unnamed =
          manifest.roles().stream().map(RoleEntry::id).filter(id -> !roleIds.contains(id)).toList();
      if (!unnamed.isEmpty()) {
        sheet.paragraph(
            "Keine Angabe zur Sichtbarkeit nennt "
                + unnamed.stream()
                    .map(id -> link(code(id), "#" + anchor(id)))
                    .collect(Collectors.joining(", "))
                + " beim Namen; die Matrix hat dafür keine Spalte. Was diese Rolle über andere"
                + " sieht, steht in ihrem Abschnitt oben.");
      }
      List<String> headers = new ArrayList<>();
      headers.add("Datenkategorie");
      roleIds.forEach(id -> headers.add(code(id)));
      headers.add("Weitere Sichtbarkeit");
      List<List<String>> rows = new ArrayList<>();
      for (DataEntry entry : manifest.data()) {
        rows.add(
            matrixRow(
                link(text(entry.title()), FactSheet.DATENKATALOG.fileName() + "#" + entry.id()),
                entry.visibility(),
                roleIds));
      }
      if (!manifest.auditEvents().isEmpty()) {
        Set<String> visibility = new LinkedHashSet<>();
        manifest.auditEvents().forEach(entry -> visibility.addAll(entry.visibility()));
        rows.add(
            matrixRow(
                link("Protokollereignisse", FactSheet.PROTOKOLLEREIGNISSE.fileName()),
                List.copyOf(visibility),
                roleIds));
      }
      sheet.table(headers, rows);
    }
    return sheet.toString();
  }

  private static String dataFlows(ComplianceManifest manifest, FactSheetProvenance provenance) {
    Sheet sheet =
        new Sheet(
            FactSheet.DATENFLUESSE,
            provenance,
            "Daten, die die Installation verlassen: Ziel, übermittelte Daten, Anlass, Abschaltbarkeit"
                + " und die zuständige Konfiguration.");
    Map<String, SettingEntry> settingKeys = settingsByKey(manifest);

    if (!manifest.dataFlows().isEmpty()) {
      sheet.heading(2, "uebersicht", "Übersicht");
      sheet.paragraph("```mermaid\n" + DataFlowDiagram.render(manifest.dataFlows()) + "```");
      sheet.paragraph(
          "Jeder Kasten ist ein Datenfluss mit seiner Kennung, jede Linie eine Verbindung, die"
              + " OPAA aufbaut. Gestrichelt: "
              + VIA_PROXY
              + ". Durchgezogen: nie über den Ausgangsproxy.");
    }
    // a single area has no heading of its own; its anchor moves onto the section heading
    Map<String, List<DataFlowEntry>> flowAreas = byArea(manifest.dataFlows(), DataFlowEntry::area);
    boolean severalAreas = flowAreas.size() > 1;
    String singleAreaAnchor =
        flowAreas.size() == 1
            ? anchored("bereich-" + anchor(flowAreas.keySet().iterator().next()), "")
            : "";
    sheet.heading(2, "datenfluesse", singleAreaAnchor + "Ausgehende Datenflüsse");
    if (manifest.dataFlows().isEmpty()) {
      sheet.paragraph(EMPTY_SECTION);
    }
    flowAreas.forEach(
        (area, entries) -> {
          if (severalAreas) {
            sheet.heading(3, "bereich-" + anchor(area), text(areaTitle(manifest, area)));
          }
          for (DataFlowEntry entry : entries) {
            sheet.heading(severalAreas ? 4 : 3, anchor(entry.id()), code(entry.id()));
            sheet.table(
                List.of("Angabe", "Inhalt"),
                List.of(
                    List.of("Gruppe", DataFlowDiagram.title(entry.group())),
                    List.of(
                        "Ausgangsproxy",
                        Boolean.TRUE.equals(entry.viaOutboundProxy())
                            ? "ja, " + VIA_PROXY
                            : "nein, nie"),
                    List.of("Ziel", text(entry.target())),
                    List.of("Übermittelte Daten", text(entry.data())),
                    List.of("Anlass", text(entry.trigger())),
                    List.of("Abschaltbarkeit", text(entry.switchOff())),
                    List.of(
                        "Konfiguration",
                        settingLinks(
                            entry.settings(), FactSheet.VOREINSTELLUNGEN.fileName(), settingKeys)),
                    List.of("Hinweise", texts(entry.notes()))));
          }
        });
    sheet.notYetCaptured(NOT_YET_CAPTURED.get(FactSheet.DATENFLUESSE));
    return sheet.toString();
  }

  private static String defaults(ComplianceManifest manifest, FactSheetProvenance provenance) {
    Sheet sheet =
        new Sheet(
            FactSheet.VOREINSTELLUNGEN,
            provenance,
            "Voreinstellungen mit Wirkung auf Daten, Sichtbarkeit, Auswertung oder Datenflüsse, mit"
                + " ausgeliefertem Standardwert und Wirkung; dazu die System-Prompts mit Prüfsumme"
                + " und die KI-Funktionen. Eine Installation kann die Einstellungen ändern.");

    sheet.heading(2, "einstellungen", "Einstellungen");
    if (manifest.settings().isEmpty()) {
      sheet.paragraph(EMPTY_SECTION);
    }
    byArea(manifest.settings(), SettingEntry::area)
        .forEach(
            (area, entries) -> {
              sheet.heading(3, "bereich-" + anchor(area), text(areaTitle(manifest, area)));
              sheet.table(
                  List.of(
                      "Schlüssel",
                      "Quelle",
                      "Standardwert",
                      "Wertebereich",
                      "Wirkung",
                      "Bezug",
                      "Hinweise"),
                  entries.stream()
                      .map(
                          entry ->
                              List.of(
                                  anchored(anchor(entry.key()), code(entry.key())),
                                  source(entry.source()),
                                  defaultValue(entry),
                                  orNone(entry.range()),
                                  text(entry.effect()),
                                  bearings(entry.bearings()),
                                  texts(entry.notes())))
                      .toList());
            });

    sheet.heading(2, "system-prompts", "System-Prompts");
    if (manifest.prompts().isEmpty()) {
      sheet.paragraph(EMPTY_SECTION);
    }
    Map<String, List<PromptEntry>> promptAreas = byArea(manifest.prompts(), PromptEntry::area);
    promptAreas.forEach(
        (area, entries) -> {
          areaHeading(sheet, manifest, area, promptAreas.size());
          sheet.table(
              List.of("Prompt", "Zweck", "Fundstelle", "Prüfsumme (SHA-256)", "Hinweise"),
              entries.stream()
                  .map(
                      entry ->
                          List.of(
                              anchored("prompt-" + anchor(entry.id()), code(entry.id())),
                              text(entry.purpose()),
                              promptLocation(entry),
                              code(entry.sha256()),
                              texts(entry.notes())))
                  .toList());
        });

    sheet.heading(2, "ki-funktionen", "KI-Funktionen");
    if (manifest.aiFunctions().isEmpty()) {
      sheet.paragraph(EMPTY_SECTION);
    }
    Map<String, List<AiFunctionEntry>> aiAreas =
        byArea(manifest.aiFunctions(), AiFunctionEntry::area);
    aiAreas.forEach(
        (area, entries) -> {
          areaHeading(sheet, manifest, area, aiAreas.size());
          sheet.table(
              List.of(
                  "Funktion",
                  "Zweck",
                  "Ausgabe",
                  "Interaktion mit Personen",
                  "Bewertung von Personen",
                  "Kennzeichnung",
                  "System-Prompts",
                  "Hinweise"),
              entries.stream().map(FactSheetRenderer::aiFunctionRow).toList());
        });

    sheet.notYetCaptured(NOT_YET_CAPTURED.get(FactSheet.VOREINSTELLUNGEN));
    return sheet.toString();
  }

  private static String deadlines(ComplianceManifest manifest, FactSheetProvenance provenance) {
    Sheet sheet =
        new Sheet(
            FactSheet.FRISTEN,
            provenance,
            "Wie lange OPAA Daten und Protokolleinträge aufbewahrt, und welcher"
                + " Konfigurationsschlüssel eine Frist setzt. Standardwerte gelten für die"
                + " Auslieferung.");
    Map<String, SettingEntry> settingKeys = settingsByKey(manifest);

    Map<String, List<String>> usedBy = new LinkedHashMap<>();
    for (DataEntry entry : manifest.data()) {
      for (String key : entry.retention().settings()) {
        usedBy
            .computeIfAbsent(key, k -> new ArrayList<>())
            .add(link(text(entry.title()), FactSheet.DATENKATALOG.fileName() + "#" + entry.id()));
      }
    }
    Map<String, List<AuditEventEntry>> eventsBy = new LinkedHashMap<>();
    for (AuditEventEntry entry : manifest.auditEvents()) {
      for (String key : entry.retention().settings()) {
        eventsBy.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
      }
    }
    eventsBy.forEach(
        (key, events) ->
            usedBy
                .computeIfAbsent(key, k -> new ArrayList<>())
                .add(eventsLabel(events, manifest.auditEvents().size())));

    sheet.heading(2, "konfigurationsschluessel", "Fristen mit Konfigurationsschlüssel");
    List<SettingEntry> retentionSettings =
        manifest.settings().stream().filter(setting -> usedBy.containsKey(setting.key())).toList();
    sheet.table(
        List.of("Schlüssel", "Quelle", "Standardwert", "Wertebereich", "Wirkung", "Gilt für"),
        retentionSettings.stream()
            .map(
                entry ->
                    List.of(
                        anchored(anchor(entry.key()), code(entry.key())),
                        source(entry.source()),
                        defaultValue(entry),
                        orNone(entry.range()),
                        text(entry.effect()),
                        String.join("; ", usedBy.get(entry.key()))))
            .toList());

    sheet.heading(2, "daten", "Fristen je Datum");
    sheet.table(
        List.of("Datum", "Frist", "Konfiguration"),
        manifest.data().stream()
            .map(
                entry ->
                    List.of(
                        link(
                            text(entry.title()),
                            FactSheet.DATENKATALOG.fileName() + "#" + entry.id()),
                        text(entry.retention().rule()),
                        settingLinks(entry.retention().settings(), "", settingKeys)))
            .toList());

    sheet.heading(2, "protokollereignisse", "Fristen der Protokollereignisse");
    Map<List<Object>, List<AuditEventEntry>> groups = new LinkedHashMap<>();
    for (AuditEventEntry entry : manifest.auditEvents()) {
      groups
          .computeIfAbsent(
              List.of(entry.retention().rule(), entry.retention().settings()),
              k -> new ArrayList<>())
          .add(entry);
    }
    sheet.table(
        List.of("Ereignisse", "Frist", "Konfiguration"),
        groups.values().stream()
            .map(
                events ->
                    List.of(
                        eventsLabel(events, manifest.auditEvents().size()),
                        text(events.getFirst().retention().rule()),
                        settingLinks(events.getFirst().retention().settings(), "", settingKeys)))
            .toList());
    return sheet.toString();
  }

  /** Storage, retention, visibility, access, deletion, evaluations and notes of a datum. */
  private static List<List<String>> factRows(DataFacts entry, Map<String, SettingEntry> settings) {
    List<List<String>> rows = new ArrayList<>();
    rows.add(List.of("Speicherort", text(entry.storage())));
    rows.add(
        List.of(
            "Frist",
            text(entry.retention().rule())
                + (entry.retention().settings().isEmpty()
                    ? ""
                    : "; Konfiguration: "
                        + settingLinks(
                            entry.retention().settings(),
                            FactSheet.FRISTEN.fileName(),
                            settings))));
    rows.add(List.of("Sichtbarkeit", texts(entry.visibility())));
    rows.add(List.of("Zugriffsweg", accessPaths(entry.accessPaths())));
    rows.add(List.of("Löschmechanismus", text(entry.deletion())));
    rows.add(List.of("Mögliche Auswertungen", text(entry.evaluations())));
    rows.add(List.of("Hinweise", texts(entry.notes())));
    return rows;
  }

  private static List<String> aiFunctionRow(AiFunctionEntry entry) {
    return List.of(
        anchored("ki-funktion-" + anchor(entry.id()), code(entry.id())),
        text(entry.purpose()),
        outputKind(entry.outputKind()),
        yesNo(entry.interactsWithPersons()),
        yesNo(entry.assessesPersons()),
        text(entry.labelling()),
        entry.prompts().isEmpty()
            ? "keine"
            : entry.prompts().stream()
                .map(id -> link(code(id), "#prompt-" + anchor(id)))
                .collect(Collectors.joining(", ")),
        texts(entry.notes()));
  }

  private static void roleSections(Sheet sheet, List<RoleEntry> entries) {
    if (entries.isEmpty()) {
      sheet.paragraph(EMPTY_SECTION);
    }
    for (RoleEntry entry : entries) {
      sheet.heading(3, anchor(entry.id()), code(entry.id()));
      sheet.paragraph("**Sieht über andere Personen:** " + text(entry.seesAboutOthers()));
      if (entry.notes() != null && !entry.notes().isEmpty()) {
        sheet.paragraph("**Hinweise:** " + texts(entry.notes()));
      }
    }
  }

  private static List<String> matrixRow(String label, List<String> visibility, List<String> roles) {
    List<String> row = new ArrayList<>();
    row.add(label);
    Set<String> named = new LinkedHashSet<>();
    for (String role : roles) {
      List<String> hits = visibility.stream().filter(v -> mentions(v, role)).toList();
      named.addAll(hits);
      row.add(hits.isEmpty() ? NOT_NAMED : texts(hits));
    }
    List<String> others = visibility.stream().filter(v -> !named.contains(v)).toList();
    row.add(others.isEmpty() ? "keine weiteren Angaben" : texts(others));
    return row;
  }

  /** Whether a visibility statement names the role, as a whole word. */
  private static boolean mentions(String visibility, String role) {
    return Pattern.compile("(?<![A-Z_])" + Pattern.quote(role) + "(?![A-Z_])")
        .matcher(visibility)
        .find();
  }

  private static String eventsLabel(List<AuditEventEntry> events, int total) {
    if (events.size() == total && total > 1) {
      return link(
          "alle " + total + " Protokollereignisse", FactSheet.PROTOKOLLEREIGNISSE.fileName());
    }
    return events.stream()
        .map(
            event ->
                link(
                    code(event.id()),
                    FactSheet.PROTOKOLLEREIGNISSE.fileName() + "#" + anchor(event.id())))
        .collect(Collectors.joining(", "));
  }

  /**
   * Setting keys as links to their anchor in {@code targetFile} ({@code ""} for this sheet), each
   * with its delivered value; a key no settings entry describes stays plain code.
   */
  private static String settingLinks(
      List<String> keys, String targetFile, Map<String, SettingEntry> settings) {
    if (keys == null || keys.isEmpty()) {
      return "keine";
    }
    return keys.stream()
        .map(
            key -> {
              SettingEntry setting = settings.get(key);
              return setting == null
                  ? code(key)
                  : link(code(key), targetFile + "#" + anchor(key))
                      + " (Standard: "
                      + defaultValue(setting)
                      + ")";
            })
        .collect(Collectors.joining(", "));
  }

  private static Map<String, SettingEntry> settingsByKey(ComplianceManifest manifest) {
    Map<String, SettingEntry> settings = new LinkedHashMap<>();
    manifest.settings().forEach(setting -> settings.put(setting.key(), setting));
    return settings;
  }

  /** The delivered value as written, followed by its reading where it is a duration. */
  static String defaultValue(SettingEntry setting) {
    String reading = DurationReading.of(setting.defaultValue(), setting.key());
    return code(setting.defaultValue()) + (reading == null ? "" : " = " + reading);
  }

  private static String promptLocation(PromptEntry entry) {
    if (entry.databaseDefault() != null && !entry.databaseDefault().isBlank()) {
      return "Standardwert der Spalte " + code(entry.databaseDefault());
    }
    return codes(entry.classes());
  }

  /**
   * The columns of the tables holding a datum, per table, either those pointing at a person or the
   * remaining ones; a table without such columns is left out.
   */
  private static String columns(List<TableRef> tables, Function<TableRef, List<String>> columns) {
    if (tables.isEmpty()) {
      return "keine Tabelle (außerhalb der Datenbank)";
    }
    String listed =
        tables.stream()
            .filter(table -> columns.apply(table) != null && !columns.apply(table).isEmpty())
            .map(table -> code(table.name()) + ": " + codes(columns.apply(table)))
            .collect(Collectors.joining("; "));
    return listed.isEmpty() ? "keine" : listed;
  }

  /**
   * How the datum is assigned to a person for the self-disclosure, per table: the columns naming
   * the person or the table it goes through, with the columns left out, or why it is left out.
   */
  private static String personPath(DataEntry entry) {
    if (entry.tables().isEmpty()) {
      return "nicht Teil der Selbstauskunft: " + orNotNamed(entry.personExcluded());
    }
    return entry.tables().stream()
        .map(table -> code(table.name()) + ": " + personPath(table.person()))
        .collect(Collectors.joining("; "));
  }

  private static String personPath(PersonPath person) {
    if (person == null) {
      return NOT_NAMED;
    }
    if (person.excluded() != null && !person.excluded().isBlank()) {
      return "nicht Teil der Selbstauskunft: " + text(person.excluded());
    }
    List<String> ways = new ArrayList<>();
    if (person.columns() != null && !person.columns().isEmpty()) {
      ways.add("über " + codes(person.columns()));
    }
    if (person.via() != null && !person.via().isBlank()) {
      ways.add("über Tabelle " + code(person.via()));
    }
    StringBuilder path = new StringBuilder(ways.isEmpty() ? NOT_NAMED : String.join(" und ", ways));
    if (person.note() != null && !person.note().isBlank()) {
      path.append(" (").append(text(person.note())).append(')');
    }
    String excluded =
        person.excludedColumnsOrEmpty().entrySet().stream()
            .map(column -> code(column.getKey()) + " (" + text(column.getValue()) + ")")
            .collect(Collectors.joining(", "));
    if (!excluded.isEmpty()) {
      path.append(", ohne ").append(excluded);
    }
    return path.toString();
  }

  private static String orNotNamed(String value) {
    return value == null || value.isBlank() ? NOT_NAMED : text(value);
  }

  private static <T> Map<String, List<T>> byArea(List<T> entries, Function<T, String> area) {
    Map<String, List<T>> grouped = new LinkedHashMap<>();
    entries.forEach(
        entry -> grouped.computeIfAbsent(area.apply(entry), k -> new ArrayList<>()).add(entry));
    return grouped;
  }

  /**
   * The heading of an area within a section without area anchors; a section of a single area has
   * none, as its title would only repeat across the sections of the sheet.
   */
  private static void areaHeading(
      Sheet sheet, ComplianceManifest manifest, String area, int areasInSection) {
    if (areasInSection > 1) {
      sheet.heading(3, null, text(areaTitle(manifest, area)));
    }
  }

  private static String areaTitle(ComplianceManifest manifest, String area) {
    return manifest.areas().getOrDefault(area, area);
  }

  private static String personalReference(PersonalReference reference) {
    return switch (reference) {
      case NONE -> "kein Personenbezug";
      case IDENTIFIABLE -> "personenbeziehbar";
      case EMPLOYEE -> "Beschäftigtendaten";
    };
  }

  private static String accessPaths(List<AccessPath> paths) {
    return paths.stream()
        .map(
            path ->
                switch (path) {
                  case UI -> "Oberfläche";
                  case API -> "API";
                  case EXPORT -> "Export";
                  case DATABASE -> "Datenbank";
                  case STORAGE -> "Ablage des Betriebs (Dateisystem, Objektspeicher, Log)";
                })
        .collect(Collectors.joining(", "));
  }

  private static String bearings(List<Bearing> bearings) {
    return bearings.stream()
        .map(
            bearing ->
                switch (bearing) {
                  case DATA -> "Daten";
                  case VISIBILITY -> "Sichtbarkeit";
                  case EVALUATION -> "Auswertung";
                  case DATA_FLOW -> "Datenfluss";
                })
        .collect(Collectors.joining(", "));
  }

  private static String source(SettingSource source) {
    return switch (source) {
      case PROPERTY -> "Konfigurationseigenschaft";
      case DATABASE -> "Verwaltung (Datenbank)";
      case CONSTANT -> "Konstante im Code";
    };
  }

  private static String outputKind(OutputKind kind) {
    return switch (kind) {
      case TEXT -> "Text";
      case AUDIO -> "Audio";
      case IMAGE -> "Bild";
    };
  }

  private static String yesNo(Boolean value) {
    return Boolean.TRUE.equals(value) ? "ja" : "nein";
  }

  /** Free text from the manifest, safe inside a table cell and as inline Markdown. */
  static String text(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace("|", "\\|")
        .replaceAll("\\s*\\R\\s*", " ")
        .strip();
  }

  private static String texts(Collection<String> values) {
    if (values == null || values.isEmpty()) {
      return NONE;
    }
    return values.stream().map(FactSheetRenderer::text).collect(Collectors.joining("; "));
  }

  static final String PAYLOAD_KEYS_LABEL = "Erlaubte Angaben in before und after";

  /** The keys of the event catalog the event's {@code before}/{@code after} may hold. */
  static String payloadKeys(AuditEventEntry entry) {
    List<String> keys = entry.payloadKeys();
    return keys == null || keys.isEmpty() ? NONE : codes(keys);
  }

  private static String orNone(String value) {
    return value == null || value.isBlank() ? NONE : text(value);
  }

  /**
   * A code span that holds any value: the fence is one backtick longer than the longest backtick
   * run inside, padded with spaces when the value starts or ends with a backtick.
   */
  static String code(String value) {
    int longest = 0;
    int run = 0;
    for (char c : value.toCharArray()) {
      run = c == '`' ? run + 1 : 0;
      longest = Math.max(longest, run);
    }
    String fence = "`".repeat(longest + 1);
    String pad = value.startsWith("`") || value.endsWith("`") ? " " : "";
    return fence + pad + value.replace("|", "\\|") + pad + fence;
  }

  private static String codes(List<String> values) {
    return values.stream().map(FactSheetRenderer::code).collect(Collectors.joining(", "));
  }

  private static String link(String label, String target) {
    return "[" + label + "](" + target + ")";
  }

  private static String anchored(String anchor, String content) {
    return "<a id=\"" + anchor + "\"></a>" + content;
  }

  private static Map<FactSheet, List<Pending>> notYetCaptured() {
    return new EnumMap<>(FactSheet.class);
  }

  /** A Markdown document under construction with the shared head of every fact sheet. */
  private static final class Sheet {

    private final StringBuilder out = new StringBuilder();

    Sheet(FactSheet sheet, FactSheetProvenance provenance, String intro) {
      out.append("---\n")
          .append("gilt-fuer: ")
          .append(provenance.version())
          .append('\n')
          .append("commit: ")
          .append(provenance.commit())
          .append('\n')
          .append("erzeugt-am: ")
          .append(provenance.generatedOn())
          .append('\n')
          .append("erzeugung: generiert\n")
          .append("---\n\n")
          .append("# Faktenblatt: ")
          .append(sheet.title())
          .append("\n\n> ")
          .append(NOTICE)
          .append("\n\n");
      paragraph(
          intro
              + " Erzeugt aus dem Fakten-Manifest von OPAA "
              + provenance.version()
              + " (Commit "
              + code(provenance.commit())
              + ") am "
              + provenance.generatedOn()
              + ".");
    }

    void heading(int level, String anchor, String title) {
      out.append("#".repeat(level)).append(' ');
      if (anchor != null) {
        out.append(anchored(anchor, ""));
      }
      out.append(title).append("\n\n");
    }

    void paragraph(String text) {
      out.append(text).append("\n\n");
    }

    void table(List<String> headers, List<List<String>> rows) {
      if (rows.isEmpty()) {
        paragraph(EMPTY_SECTION);
        return;
      }
      out.append("| ").append(String.join(" | ", headers)).append(" |\n");
      out.append("|").append("---|".repeat(headers.size())).append('\n');
      for (List<String> row : rows) {
        out.append("| ").append(String.join(" | ", row)).append(" |\n");
      }
      out.append('\n');
    }

    void notYetCaptured(List<Pending> items) {
      if (items == null || items.isEmpty()) {
        return;
      }
      heading(2, null, NOT_YET_CAPTURED_HEADING);
      paragraph(
          "Das Fakten-Manifest beschreibt diese Angaben noch nicht. Bis dahin ist dieses Blatt"
              + " unvollständig; eine fehlende Angabe bedeutet nicht, dass es sie nicht gibt.");
      for (Pending item : items) {
        out.append("- ")
            .append(item.text())
            .append(" (")
            .append(link("#" + item.issue(), ISSUES + item.issue()))
            .append(")\n");
      }
      out.append('\n');
    }

    @Override
    public String toString() {
      return out.toString().stripTrailing() + "\n";
    }
  }
}
