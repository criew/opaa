package io.opaa.settings;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditCorrelation;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.common.TooManyRequestsException;
import io.opaa.ratelimit.InstallationCounters;
import io.opaa.ratelimit.RateLimitService.Decision;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exports every {@link SettingsSection} into one {@link SettingsDocument} and applies such a
 * document, system administration only (ADR-0060, Entscheidung 12).
 *
 * <p>An import runs in one transaction: each section the document carries and that differs from the
 * installation goes through {@link SettingsSection#apply}, so a refused value rolls back every
 * section before it. The preview runs the very same path and rolls back at the end, so it cannot
 * judge a document differently than the import. A section the document does not carry stays as it
 * is, a key a section does not carry keeps its value, and a document equal to the installation
 * changes and writes nothing. The changes of one import share a correlation reference with its
 * {@link AuditEventType#SETTINGS_IMPORTED} entry. Preview and import together are bounded by an
 * installation-wide counter ({@link #CALLS_PER_WINDOW} per {@link #WINDOW}), counted before the
 * transaction so a rolled-back preview still counts.
 */
@Service
public class SettingsTransferService {

  public static final String FORMAT = "opaa-settings";
  public static final int FORMAT_VERSION = 1;

  static final String COUNTER_KEY = "settings-transfer";
  static final Duration WINDOW = Duration.ofHours(1);
  static final int CALLS_PER_WINDOW = 30;

  static final String INVALID = "INVALID";
  static final String UNKNOWN = "UNKNOWN";

  private static final String ADMIN_ONLY =
      "Nur die Systemverwaltung darf Einstellungen exportieren und importieren";
  private static final UUID OBJECT_ID =
      UUID.nameUUIDFromBytes("settings".getBytes(StandardCharsets.UTF_8));
  private static final String OBJECT_LABEL = "Einstellungen";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final List<SettingsSection> sections;
  private final TransactionTemplate transactions;
  private final InstallationCounters counters;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public SettingsTransferService(
      List<SettingsSection> sections,
      PlatformTransactionManager transactionManager,
      InstallationCounters counters,
      AuditEventRecorder audit,
      Clock clock) {
    this.sections = sorted(sections);
    this.transactions = new TransactionTemplate(transactionManager);
    this.counters = counters;
    this.audit = audit;
    this.clock = clock;
  }

  /** The registered sections by key; the order of the document and of every result. */
  public List<SettingsSection> sections() {
    return sections;
  }

  /** Every section's current values, read in one transaction. */
  public SettingsDocument export(CurrentUser actor) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    TransactionTemplate readOnly = new TransactionTemplate(transactions.getTransactionManager());
    readOnly.setReadOnly(true);
    Map<String, Map<String, Object>> exported =
        readOnly.execute(
            status -> {
              Map<String, Map<String, Object>> values = new LinkedHashMap<>();
              for (SettingsSection section : sections) {
                values.put(section.key(), plainObject(tree(section.export(actor))));
              }
              return values;
            });
    return new SettingsDocument(FORMAT, FORMAT_VERSION, Instant.now(clock), exported);
  }

  /** What {@link #importDocument} would change, without changing anything. */
  public SettingsImportResult preview(CurrentUser actor, SettingsDocument document) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    count();
    return transactions.execute(
        status -> {
          List<SettingsImportResult.Change> changes = apply(actor, document);
          status.setRollbackOnly();
          return new SettingsImportResult(false, changes);
        });
  }

  /** Applies {@code document} atomically and records the import when it changed something. */
  public SettingsImportResult importDocument(CurrentUser actor, SettingsDocument document) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    count();
    String correlationRef = "settings-import:" + UUID.randomUUID();
    return transactions.execute(
        status ->
            AuditCorrelation.within(
                correlationRef,
                () -> {
                  List<SettingsImportResult.Change> changes = apply(actor, document);
                  if (!changes.isEmpty()) {
                    record(actor, changes);
                  }
                  return new SettingsImportResult(true, changes);
                }));
  }

  private void count() {
    Decision decision = counters.tryCount(COUNTER_KEY, WINDOW, CALLS_PER_WINDOW);
    if (!decision.allowed()) {
      throw new TooManyRequestsException(decision.retryAfterSeconds());
    }
  }

  private List<SettingsImportResult.Change> apply(CurrentUser actor, SettingsDocument document) {
    Map<String, Map<String, Object>> imported = requireReadable(document);
    List<SettingsImportResult.Change> changes = new ArrayList<>();
    for (SettingsSection section : sections) {
      Map<String, Object> values = imported.get(section.key());
      if (values != null) {
        changes.addAll(applySection(actor, section, values));
      }
    }
    return changes;
  }

  private List<SettingsImportResult.Change> applySection(
      CurrentUser actor, SettingsSection section, Map<String, Object> values) {
    ObjectNode current = tree(section.export(actor));
    Set<String> unknown = new TreeSet<>(values.keySet());
    unknown.removeAll(current.propertyNames());
    if (!unknown.isEmpty()) {
      throw FieldValidationException.of(
          section.key(),
          UNKNOWN,
          "Der Abschnitt „"
              + section.label()
              + "“ kennt diese Angaben nicht: "
              + String.join(", ", unknown)
              + ".");
    }
    ObjectNode merged = current.deepCopy();
    merged.setAll(tree(values));
    if (merged.equals(current)) {
      return List.of();
    }
    section.apply(actor, new SettingsValues(section.key(), plainObject(merged)));
    ObjectNode after = tree(section.export(actor));
    List<SettingsImportResult.Change> changes = new ArrayList<>();
    for (String key : current.propertyNames()) {
      if (!Objects.equals(current.get(key), after.get(key))) {
        changes.add(
            new SettingsImportResult.Change(
                section.key(),
                section.label(),
                key,
                plain(current.get(key)),
                plain(after.get(key))));
      }
    }
    return changes;
  }

  /** The sections of a readable document; refuses another format, version or unknown sections. */
  private Map<String, Map<String, Object>> requireReadable(SettingsDocument document) {
    List<FieldError> errors = new ArrayList<>();
    if (!FORMAT.equals(document.format())) {
      errors.add(
          new FieldError("format", INVALID, "Die Datei ist kein Einstellungsdokument von OPAA."));
    }
    if (!Integer.valueOf(FORMAT_VERSION).equals(document.formatVersion())) {
      errors.add(
          new FieldError(
              "formatVersion",
              INVALID,
              "Diese Installation liest Einstellungsdokumente der Version "
                  + FORMAT_VERSION
                  + ", die Datei hat "
                  + document.formatVersion()
                  + "."));
    }
    if (document.sections() == null) {
      errors.add(new FieldError("sections", INVALID, "Die Datei enthält keine Abschnitte."));
    } else {
      Set<String> known = new HashSet<>();
      sections.forEach(section -> known.add(section.key()));
      Set<String> unknown = new TreeSet<>(document.sections().keySet());
      unknown.removeAll(known);
      if (!unknown.isEmpty()) {
        errors.add(
            new FieldError(
                "sections",
                UNKNOWN,
                "Diese Installation kennt diese Abschnitte nicht: "
                    + String.join(", ", unknown)
                    + "."));
      }
    }
    if (!errors.isEmpty()) {
      throw new FieldValidationException("Das Einstellungsdokument ist nicht lesbar.", errors);
    }
    return document.sections();
  }

  private void record(CurrentUser actor, List<SettingsImportResult.Change> changes) {
    List<String> changedSections =
        changes.stream().map(SettingsImportResult.Change::section).distinct().toList();
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("formatVersion", FORMAT_VERSION);
    after.put("sections", changedSections);
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.SETTINGS_IMPORTED)
            .object(AuditObjectType.SYSTEM_SETTING, OBJECT_ID, OBJECT_LABEL)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /**
   * {@code value} as a JSON tree read back from its text, so a number compares equal whatever Java
   * type held it ({@code 36} as {@code Integer} and as {@code Long}).
   */
  private static ObjectNode tree(Object value) {
    JsonNode node = JSON.readTree(JSON.writeValueAsString(value));
    if (!(node instanceof ObjectNode object)) {
      throw new IllegalStateException("a settings section must be a JSON object");
    }
    return object;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> plainObject(ObjectNode node) {
    return JSON.convertValue(node, LinkedHashMap.class);
  }

  private static Object plain(JsonNode node) {
    return node == null || node.isNull() ? null : JSON.convertValue(node, Object.class);
  }

  private static List<SettingsSection> sorted(List<SettingsSection> sections) {
    List<SettingsSection> sorted =
        sections.stream().sorted(Comparator.comparing(SettingsSection::key)).toList();
    Set<String> keys = new HashSet<>();
    for (SettingsSection section : sorted) {
      if (!keys.add(section.key())) {
        throw new IllegalStateException("two settings sections named " + section.key());
      }
    }
    return sorted;
  }
}
