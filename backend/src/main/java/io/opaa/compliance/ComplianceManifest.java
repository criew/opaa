package io.opaa.compliance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

/**
 * The compliance fact manifest (ADR-0052): what OPAA stores, records, defaults to and sends out, as
 * far as it does not follow from the code. One YAML file per area under {@code
 * compliance/manifest/} on the classpath, merged by {@link ComplianceManifestLoader}. The nested
 * records are the schema: a component carrying {@code @NotBlank}, {@code @NotNull} or
 * {@code @NotEmpty} is a mandatory field, and a key without a component is rejected.
 *
 * <p>Keys are code identifiers (enum constants, table and column names, configuration keys, class
 * names); values are German text for the authority. {@code area} is the file name without {@code
 * .yaml} and is set by the loader, never written in a file.
 *
 * @param areas area to its German title
 */
public record ComplianceManifest(
    Map<String, String> areas,
    List<DataEntry> data,
    List<NonPersonalTable> nonPersonalTables,
    List<AuditEventEntry> auditEvents,
    List<RoleEntry> roles,
    List<RoleEntry> capabilities,
    List<SettingEntry> settings,
    List<PromptEntry> prompts,
    List<DataFlowEntry> dataFlows,
    List<AiFunctionEntry> aiFunctions) {

  public ComplianceManifest {
    areas = Map.copyOf(areas);
    data = List.copyOf(data);
    nonPersonalTables = List.copyOf(nonPersonalTables);
    auditEvents = List.copyOf(auditEvents);
    roles = List.copyOf(roles);
    capabilities = List.copyOf(capabilities);
    settings = List.copyOf(settings);
    prompts = List.copyOf(prompts);
    dataFlows = List.copyOf(dataFlows);
    aiFunctions = List.copyOf(aiFunctions);
  }

  /** How closely a datum points at a person ("Personenbezug"). */
  public enum PersonalReference {
    /** No person can be identified from it. */
    NONE,
    /** Identifies a person or is linkable to one (id, pseudonym, name, address, free text). */
    IDENTIFIABLE,
    /** Describes what a person did or when - the kind of data an employee council weighs. */
    EMPLOYEE
  }

  /** Where a datum can be read ("Zugriffsweg"). */
  public enum AccessPath {
    UI,
    API,
    EXPORT,
    /** Directly in the application database. */
    DATABASE,
    /**
     * Directly in a store the operator runs outside the database: file system, object store, log.
     */
    STORAGE
  }

  /** What a default bears on ("Bezug"). */
  public enum Bearing {
    DATA,
    VISIBILITY,
    EVALUATION,
    DATA_FLOW
  }

  /** What a model produces in an {@link AiFunctionEntry}. */
  public enum OutputKind {
    TEXT,
    AUDIO,
    IMAGE
  }

  /** The kind of counterpart a {@link DataFlowEntry} reaches, one group per kind. */
  public enum DataFlowGroup {
    /** A model role or a model endpoint the administration checks. */
    MODEL,
    /** A source of a library, the authorization or token server of a connection, an MCP server. */
    CONNECTOR,
    /** The mail server. */
    MAIL,
    /** An identity provider or the directory behind it. */
    IDENTITY,
    /** A store holding data of the installation itself. */
    STORAGE
  }

  /** Where the value of a setting is held. */
  public enum SettingSource {
    /** A Spring property, key as in {@code application.yml}. */
    PROPERTY,
    /** A column of a settings table changed in the administration, key {@code table.column}. */
    DATABASE,
    /** A constant in the code, key {@code fully.qualified.Class#FIELD}. */
    CONSTANT
  }

  /**
   * Shared mandatory facts of a stored datum; {@link DataEntry} and {@link AuditEventEntry} carry
   * them.
   */
  public interface DataFacts {
    String area();

    String id();

    String purpose();

    PersonalReference personalReference();

    String storage();

    Retention retention();

    List<String> visibility();

    List<AccessPath> accessPaths();

    String deletion();

    String evaluations();

    List<String> notes();
  }

  /**
   * How long a datum is kept.
   *
   * @param rule the period in words, or the event that ends it
   * @param settings keys of {@link SettingEntry settings} that set the period; empty when nothing
   *     configures it
   */
  public record Retention(@NotBlank String rule, @NotNull List<@NotBlank String> settings) {}

  /**
   * A table of the schema with every one of its columns, split by whether it points at a person.
   *
   * @param nonPersonalColumns the remaining columns; absent when every column points at a person
   * @param person how a row is assigned to a person for the self-disclosure ("Weg zur Person"), or
   *     why the table is left out of it; mandatory, which {@code ComplianceManifestTest} checks so
   *     that a gap is reported with the YAML to paste
   */
  public record TableRef(
      @NotBlank String name,
      @NotNull List<@NotBlank String> personalColumns,
      List<@NotBlank String> nonPersonalColumns,
      @Valid PersonPath person) {

    /**
     * The columns the self-disclosure of a person holds: the personal columns minus those excluded
     * by {@link PersonPath#excludedColumns()}; none when the table has no path to the person. A new
     * personal column is therefore part of the disclosure until it is excluded with a reason.
     */
    public List<String> disclosedColumns() {
      if (person == null || !person.reachesPerson()) {
        return List.of();
      }
      Map<String, String> excluded = person.excludedColumnsOrEmpty();
      return personalColumns.stream().filter(column -> !excluded.containsKey(column)).toList();
    }
  }

  /**
   * How the rows of a table are assigned to a person ("Weg zur Person"): a path by {@code columns},
   * {@code via} or both (two paths), or {@code excluded}.
   *
   * @param columns personal columns of the table that name the person (id, pseudonym)
   * @param via another table, of this or another data entry, through which a row reaches the
   *     person; it has a path of its own
   * @param excluded why the whole table is not part of the self-disclosure, in German
   * @param excludedColumns column of the table to why it is not part of the self-disclosure, only
   *     with {@code columns} or {@code via}
   * @param note what the path does not say by itself, in German
   */
  public record PersonPath(
      List<@NotBlank String> columns,
      String via,
      String excluded,
      Map<@NotBlank String, @NotBlank String> excludedColumns,
      String note) {

    /** Whether a row reaches the person, through its own columns or another table. */
    public boolean reachesPerson() {
      return (columns != null && !columns.isEmpty()) || (via != null && !via.isBlank());
    }

    /** {@link #excludedColumns()}, empty when absent. */
    public Map<String, String> excludedColumnsOrEmpty() {
      return excludedColumns == null ? Map.of() : excludedColumns;
    }
  }

  /**
   * A category of stored data ("Datum"), spanning the tables that hold it; {@code tables} is empty
   * for data held outside the database (object storage, logs). A datum with at least one table
   * reaching the person is a category of the self-disclosure, named by its {@code id}.
   *
   * @param personExcluded why a datum without table is not part of the self-disclosure; only and
   *     always when {@code tables} is empty
   */
  public record DataEntry(
      @NotBlank String area,
      @NotBlank @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String id,
      @NotBlank String title,
      @NotBlank String purpose,
      @NotNull PersonalReference personalReference,
      @NotNull List<@Valid TableRef> tables,
      @NotBlank String storage,
      @NotNull @Valid Retention retention,
      @NotEmpty List<@NotBlank String> visibility,
      @NotEmpty List<@NotNull AccessPath> accessPaths,
      @NotBlank String deletion,
      @NotBlank String evaluations,
      List<@NotBlank String> notes,
      String personExcluded)
      implements DataFacts {

    /** Whether the datum is a category of the self-disclosure. */
    public boolean selfDisclosed() {
      return tables.stream().anyMatch(t -> t.person() != null && t.person().reachesPerson());
    }
  }

  /**
   * A table without any column pointing at a person, marked as such as a whole; {@code columns}
   * names all of them, so a new column is a conscious decision.
   */
  public record NonPersonalTable(String area, String table, List<String> columns) {}

  /**
   * One {@code AuditEventType} value. The facts every event shares come from the {@code defaults}
   * of its file; an entry names only its trigger and what it adds.
   *
   * @param recordFields the fields of the protocol record the event fills
   * @param readers who reads the event, and for which purpose
   * @param details what {@code before}, {@code after}, subject and reason hold for this event
   */
  public record AuditEventEntry(
      @NotBlank String area,
      @NotBlank String id,
      @NotBlank String trigger,
      @NotEmpty List<@NotBlank String> recordFields,
      @NotEmpty List<@NotBlank String> readers,
      String details,
      @NotBlank String purpose,
      @NotNull PersonalReference personalReference,
      @NotBlank String storage,
      @NotNull @Valid Retention retention,
      @NotEmpty List<@NotBlank String> visibility,
      @NotEmpty List<@NotNull AccessPath> accessPaths,
      @NotBlank String deletion,
      @NotBlank String evaluations,
      List<@NotBlank String> notes)
      implements DataFacts {}

  /**
   * A {@code SystemRole} or {@code Capability} value.
   *
   * @param seesAboutOthers what the holder sees about other persons ("was sieht wer über mich")
   */
  public record RoleEntry(
      @NotBlank String area,
      @NotBlank String id,
      @NotBlank String seesAboutOthers,
      List<@NotBlank String> notes) {}

  /**
   * A default with effect on data, visibility, evaluation or data flows ("Voreinstellung"); a
   * retention period is one, referenced from {@link Retention#settings()}. A standard model is one
   * too, with the model and the address of its role as keys.
   *
   * @param defaultValue the delivered value, as written in the code or changeset; for a {@code
   *     PROPERTY} the base document of {@code application.yml} resolved without any environment,
   *     {@code ""} when it is empty
   * @param range the admissible values, where the code bounds them
   * @param helm where the Helm chart sets the key, when it does
   */
  public record SettingEntry(
      @NotBlank String area,
      @NotBlank String key,
      @NotNull SettingSource source,
      @NotNull String defaultValue,
      String range,
      @NotBlank String effect,
      @NotEmpty List<@NotNull Bearing> bearings,
      @Valid HelmValue helm,
      List<@NotBlank String> notes) {}

  /**
   * The value of the Helm chart's {@code values.yaml} that sets a key of a {@link SettingEntry}.
   *
   * @param values the dotted path in {@code values.yaml}
   * @param defaultValue its default there, a list joined by commas and {@code ""} for an empty one
   */
  public record HelmValue(@NotBlank String values, @NotNull String defaultValue) {}

  /**
   * A system prompt sent to a model, held either in code or as a database default ("Fundstelle":
   * exactly one of the two). {@code sha256} is {@link PromptChecksum#of(PromptEntry)}, so any
   * change of its wording changes the checksum.
   *
   * @param classes the binary names of the classes holding the prompt text
   * @param databaseDefault {@code table.column} whose changelog default is the prompt text
   */
  public record PromptEntry(
      @NotBlank String area,
      @NotBlank String id,
      @NotBlank String purpose,
      List<@NotBlank String> classes,
      String databaseDefault,
      @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sha256,
      List<@NotBlank String> notes) {

    /** Validated as property {@code locatedOnce}. */
    @AssertTrue
    public boolean isLocatedOnce() {
      boolean inCode = classes != null && !classes.isEmpty();
      boolean inDatabase = databaseDefault != null && !databaseDefault.isBlank();
      return inCode != inDatabase;
    }
  }

  /**
   * A kind of data leaving the installation ("ausgehender Datenfluss").
   *
   * @param data the data transmitted
   * @param trigger the occasion of the transmission
   * @param switchOff whether and how the flow can be switched off
   * @param settings keys of the settings that control it; empty when none does
   * @param group the kind of counterpart, which groups the flow in the overview diagram
   * @param viaOutboundProxy whether the connection takes the outbound proxy when one is configured
   *     and the target is not excluded from it
   */
  public record DataFlowEntry(
      @NotBlank String area,
      @NotBlank String id,
      @NotNull DataFlowGroup group,
      @NotNull Boolean viaOutboundProxy,
      @NotBlank String target,
      @NotBlank String data,
      @NotBlank String trigger,
      @NotBlank String switchOff,
      @NotNull List<@NotBlank String> settings,
      List<@NotBlank String> notes) {}

  /**
   * A function in which a model produces output ("KI-Funktion"): the facts an authority needs for
   * its own classification under the AI Act. The classification itself is the authority's call and
   * has no field here.
   *
   * @param outputKind what the model produces
   * @param interactsWithPersons whether a person interacts with the model directly
   * @param assessesPersons whether the function evaluates or profiles persons
   * @param labelling how the output is marked as generated (Art. 50), or that it is not
   * @param prompts ids of the {@link PromptEntry prompts} it sends
   */
  public record AiFunctionEntry(
      @NotBlank String area,
      @NotBlank String id,
      @NotBlank String purpose,
      @NotNull OutputKind outputKind,
      @NotNull Boolean interactsWithPersons,
      @NotNull Boolean assessesPersons,
      @NotBlank String labelling,
      @NotNull List<@NotBlank String> prompts,
      List<@NotBlank String> notes) {}
}
