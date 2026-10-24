package io.opaa.compliance;

import io.opaa.compliance.StageRules.Assessment;
import io.opaa.compliance.StageRules.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * The manifest delta of an update matched against the configuration export of one instance (the
 * JSON form of the "Auskunft über die Datenerhebung"): whether each change of the update concerns
 * that instance, and, given an earlier export, what the department itself changed between both
 * exports, assessed with the same {@link StageRules}. Reads files only; the instance is never
 * asked.
 *
 * <p>In doubt a change concerns the instance: only an overridden default and a data flow that is
 * switched off, or a new connector flow nobody has set up, are marked as not concerning it.
 *
 * @param delta the update as the delta names its two versions
 * @param export the instance the update is matched against
 * @param previousExport the earlier export of the same instance, {@code null} without one
 * @param entries every change of the delta, in its order, with whether it concerns the instance
 * @param ownChanges what the department changed between both exports; empty without an earlier one
 * @param notes conditions the reader has to know, such as an export of another version
 */
public record InstanceMatch(
    Update delta,
    Export export,
    Export previousExport,
    List<Entry> entries,
    List<OwnChange> ownChanges,
    List<String> notes) {

  public InstanceMatch {
    Objects.requireNonNull(delta, "delta");
    Objects.requireNonNull(export, "export");
    entries = List.copyOf(entries);
    ownChanges = List.copyOf(ownChanges);
    notes = List.copyOf(notes);
  }

  /** Whether a change of the update reaches the instance. */
  public enum Relevance {
    CONCERNED("betrifft diese Instanz"),
    OVERRIDDEN("betrifft sie nicht (überschrieben)"),
    NOT_SWITCHED_ON("betrifft sie nicht (nicht eingeschaltet)");

    private final String title;

    Relevance(String title) {
      this.title = title;
    }

    /** The German label. */
    public String title() {
      return title;
    }
  }

  /** The two versions of the delta. */
  public record Update(String fromVersion, String fromCommit, String toVersion, String toCommit) {}

  /** One configuration export: the version it was taken on, its commit and when. */
  public record Export(String source, String version, String commit, String generatedAt) {}

  /**
   * One change of the delta with its proposal, as the delta holds it, and whether it concerns the
   * instance.
   *
   * @param reason why the change concerns the instance or not
   */
  public record Entry(
      String group,
      String section,
      String id,
      String title,
      String kind,
      String field,
      JsonNode before,
      JsonNode after,
      JsonNode assessment,
      Relevance relevance,
      String reason) {}

  /**
   * A change the department made between two exports.
   *
   * @param section {@code settings}, {@code dataFlows} or {@code modelProviders}
   * @param id the setting key, data flow or model role with its name
   * @param change what changed, in plain German
   */
  public record OwnChange(
      String section,
      String id,
      String change,
      String before,
      String after,
      Assessment assessment) {}

  /**
   * Matches {@code delta} (the JSON of {@code ./gradlew complianceDelta}) against {@code export};
   * {@code previous} is an earlier export of the same instance or {@code null}.
   */
  public static InstanceMatch of(
      JsonNode delta, Export exportInfo, JsonNode export, Export previousInfo, JsonNode previous) {
    if (!delta.path("changes").isArray()) {
      throw new IllegalArgumentException("the manifest delta holds no list 'changes'");
    }
    requireExport(export, exportInfo);
    if (previous != null) {
      requireExport(previous, previousInfo);
    }
    Update update =
        new Update(
            text(delta.path("from").path("version")),
            text(delta.path("from").path("commit")),
            text(delta.path("to").path("version")),
            text(delta.path("to").path("commit")));
    Map<String, JsonNode> settings = byKey(export.path("instance").path("settings"), "key");
    Map<String, JsonNode> flows = byKey(export.path("instance").path("dataFlows"), "id");
    List<Entry> entries = new ArrayList<>();
    for (JsonNode change : delta.path("changes")) {
      entries.add(entry(change, settings, flows));
    }
    List<String> notes = new ArrayList<>();
    if (!withoutPrefix(update.fromVersion()).equals(withoutPrefix(exportInfo.version()))
        && !update.fromCommit().equals(exportInfo.commit())) {
      notes.add(
          "Der Export stammt von Version "
              + exportInfo.version()
              + ", das Delta beginnt bei "
              + update.fromVersion()
              + ". Der Abgleich setzt voraus, dass die Instanz den Ausgangsstand des Deltas"
              + " betreibt; Einstellungen, die es dort noch nicht gibt, gelten als betroffen.");
    }
    List<OwnChange> own =
        previous == null ? List.of() : ownChanges(previous, export, previousInfo, notes);
    return new InstanceMatch(update, exportInfo, previousInfo, entries, own, notes);
  }

  private static Entry entry(
      JsonNode change, Map<String, JsonNode> settings, Map<String, JsonNode> flows) {
    String section = text(change.path("section"));
    String id = text(change.path("id"));
    String kind = text(change.path("kind"));
    String field = change.path("field").isString() ? change.path("field").asString() : null;
    Relevance relevance = Relevance.CONCERNED;
    String reason;
    switch (section) {
      case "settings" -> {
        JsonNode setting = settings.get(id);
        if (!"CHANGED".equals(kind)
            || !("defaultValue".equals(field) || "helm.defaultValue".equals(field))) {
          reason = "Die Änderung betrifft die Einstellung selbst und gilt für jede Instanz.";
        } else if (setting == null) {
          reason = "Der Export nennt die Einstellung nicht; im Zweifel betroffen.";
        } else if (setting.path("deviates").asBoolean(false)) {
          relevance = Relevance.OVERRIDDEN;
          reason =
              "Die Instanz setzt "
                  + FactSheetRenderer.code(text(setting.path("effectiveValue")))
                  + " statt der Voreinstellung; die neue Voreinstellung wirkt dort nicht.";
        } else if (!setting.path("sameOnAllInstances").asBoolean(true)
            || !setting.path("effectiveValue").isString()) {
          reason = "Der Wert der Instanz ist im Export nicht ermittelt; im Zweifel betroffen.";
        } else {
          reason = "Die Instanz nutzt die Voreinstellung.";
        }
      }
      case "dataFlows" -> {
        JsonNode flow = flows.get(id);
        JsonNode active = flow == null ? null : flow.path("active");
        if (active != null && active.isBoolean()) {
          if (active.asBoolean()) {
            reason = "Der Datenfluss ist in der Instanz eingeschaltet.";
          } else {
            relevance = Relevance.NOT_SWITCHED_ON;
            reason =
                "Der Datenfluss ist in der Instanz ausgeschaltet; er betrifft sie, sobald sie ihn"
                    + " einschaltet.";
          }
        } else if (flow != null) {
          reason =
              "Der Datenfluss entsteht auf Handlung einer Person; kein Schalter entscheidet ihn.";
        } else if ("ADDED".equals(kind)
            && "CONNECTOR".equals(text(change.path("after").path("group")))) {
          relevance = Relevance.NOT_SWITCHED_ON;
          reason =
              "Neuer Datenfluss eines Konnektors; er entsteht erst, wenn die Instanz eine"
                  + " Bibliothek oder einen Zugang dieser Art einrichtet.";
        } else {
          reason = "Der Export kennt den Datenfluss nicht; im Zweifel betroffen.";
        }
      }
      default -> reason = "Änderung des Produkts; sie gilt für jede Instanz.";
    }
    return new Entry(
        text(change.path("group")),
        section,
        id,
        text(change.path("title")),
        kind,
        field,
        change.path("before"),
        change.path("after"),
        change.path("assessment"),
        relevance,
        reason);
  }

  private static List<OwnChange> ownChanges(
      JsonNode previous, JsonNode current, Export previousInfo, List<String> notes) {
    Context context =
        ManifestDelta.context(snapshot(previous, previousInfo), snapshot(current, previousInfo));
    List<OwnChange> changes = new ArrayList<>();
    Map<String, JsonNode> before = byKey(previous.path("instance").path("settings"), "key");
    Map<String, JsonNode> after = byKey(current.path("instance").path("settings"), "key");
    List<String> undetermined = new ArrayList<>();
    for (String key : new TreeSet<>(before.keySet())) {
      JsonNode now = after.get(key);
      if (now == null) {
        continue;
      }
      JsonNode old = before.get(key);
      if (!old.path("effectiveValue").isString() || !now.path("effectiveValue").isString()) {
        if (old.path("effectiveValue").isString() != now.path("effectiveValue").isString()) {
          undetermined.add(key);
        }
        continue;
      }
      String b = old.path("effectiveValue").asString();
      String a = now.path("effectiveValue").asString();
      if (!b.strip().equals(a.strip())) {
        changes.add(
            new OwnChange(
                "settings",
                key,
                "Wert der Einstellung geändert",
                b,
                a,
                StageRules.changed(
                    "settings", key, "defaultValue", b, a, Map.of(), Map.of(), context)));
      }
    }
    if (!undetermined.isEmpty()) {
      notes.add(
          "Nicht vergleichbar, weil ein Export den Wert nicht ermittelt hat: "
              + String.join(", ", undetermined.stream().map(FactSheetRenderer::code).toList())
              + ".");
    }
    flowChanges(previous, current, context, changes);
    modelChanges(previous, current, context, changes);
    if (!text(previous.path("opaaVersion")).equals(text(current.path("opaaVersion")))) {
      notes.add(
          "Die beiden Exporte stammen von verschiedenen Versionen; Einstellungen und Datenflüsse,"
              + " die nur eine der beiden kennt, sind Änderungen des Produkts und stehen nicht unter"
              + " den Änderungen der Dienststelle.");
    }
    return changes;
  }

  private static void flowChanges(
      JsonNode previous, JsonNode current, Context context, List<OwnChange> changes) {
    Map<String, JsonNode> before = byKey(previous.path("instance").path("dataFlows"), "id");
    Map<String, JsonNode> after = byKey(current.path("instance").path("dataFlows"), "id");
    for (String id : new TreeSet<>(before.keySet())) {
      JsonNode old = before.get(id).path("active");
      JsonNode now = after.containsKey(id) ? after.get(id).path("active") : null;
      if (now == null
          || !old.isBoolean()
          || !now.isBoolean()
          || old.asBoolean() == now.asBoolean()) {
        continue;
      }
      changes.add(
          now.asBoolean()
              ? new OwnChange(
                  "dataFlows",
                  id,
                  "Datenfluss eingeschaltet",
                  "aus",
                  "ein",
                  StageRules.added("dataFlows", id, Map.of(), context))
              : new OwnChange(
                  "dataFlows",
                  id,
                  "Datenfluss ausgeschaltet",
                  "ein",
                  "aus",
                  StageRules.removed("dataFlows", id, Map.of(), context)));
    }
  }

  private static void modelChanges(
      JsonNode previous, JsonNode current, Context context, List<OwnChange> changes) {
    Map<String, JsonNode> before = providers(previous);
    Map<String, JsonNode> after = providers(current);
    TreeSet<String> ids = new TreeSet<>(before.keySet());
    ids.addAll(after.keySet());
    for (String id : ids) {
      JsonNode old = before.get(id);
      JsonNode now = after.get(id);
      boolean wasActive = old != null && old.path("active").asBoolean(false);
      boolean isActive = now != null && now.path("active").asBoolean(false);
      if (!wasActive && isActive) {
        changes.add(
            new OwnChange(
                "modelProviders",
                id,
                "Modellanbieter in Gebrauch genommen",
                old == null ? "–" : "inaktiv",
                text(now.path("baseUrl")),
                StageRules.added("dataFlows", id, Map.of(), context)));
        continue;
      }
      if (wasActive && !isActive) {
        changes.add(
            new OwnChange(
                "modelProviders",
                id,
                "Modellanbieter außer Gebrauch",
                text(old.path("baseUrl")),
                now == null ? "–" : "inaktiv",
                StageRules.removed("dataFlows", id, Map.of(), context)));
        continue;
      }
      if (old == null || now == null || !isActive) {
        continue;
      }
      String fromUrl = text(old.path("baseUrl"));
      String toUrl = text(now.path("baseUrl"));
      if (!fromUrl.equals(toUrl)) {
        changes.add(
            new OwnChange(
                "modelProviders",
                id,
                "Adresse geändert",
                fromUrl,
                toUrl,
                StageRules.changed(
                    "dataFlows", id, "target", fromUrl, toUrl, Map.of(), Map.of(), context)));
      }
      String fromModel = text(old.path("model"));
      String toModel = text(now.path("model"));
      if (!fromModel.equals(toModel)) {
        changes.add(
            new OwnChange(
                "modelProviders",
                id,
                "Modell geändert",
                fromModel,
                toModel,
                StageRules.changed(
                    "settings",
                    id + ".model",
                    "defaultValue",
                    fromModel,
                    toModel,
                    Map.of(),
                    Map.of(),
                    context)));
      }
    }
  }

  /** Model endpoints by role and name, the name the administration gave it. */
  private static Map<String, JsonNode> providers(JsonNode export) {
    Map<String, JsonNode> found = new TreeMap<>();
    for (JsonNode provider : export.path("instance").path("modelProviders")) {
      String name = text(provider.path("name"));
      String id = text(provider.path("role")) + (name.isEmpty() ? "" : " " + name);
      found.put(id, provider);
    }
    return found;
  }

  private static ManifestSnapshot snapshot(JsonNode export, Export info) {
    return ManifestSnapshot.fromJsonNode(export, info.source());
  }

  private static void requireExport(JsonNode export, Export info) {
    if (!export.path("manifest").isObject() || !export.path("instance").isObject()) {
      throw new IllegalArgumentException(
          info.source()
              + " is no configuration export: it needs 'manifest' and 'instance' as the endpoint"
              + " /api/v1/admin/compliance/data-collection-disclosure writes them");
    }
  }

  private static Map<String, JsonNode> byKey(JsonNode list, String field) {
    Map<String, JsonNode> found = new LinkedHashMap<>();
    for (JsonNode node : list) {
      found.put(text(node.path(field)), node);
    }
    return found;
  }

  /** A version as a tag names it ({@code v1.2.3}) and as the backend reports it ({@code 1.2.3}). */
  private static String withoutPrefix(String version) {
    return version.startsWith("v") ? version.substring(1) : version;
  }

  /** The text of {@code node}, empty when it is absent or no text. */
  static String text(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull() ? "" : node.asString("");
  }
}
