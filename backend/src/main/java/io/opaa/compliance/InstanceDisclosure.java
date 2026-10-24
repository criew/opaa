package io.opaa.compliance;

import io.opaa.compliance.ComplianceManifest.DataFlowGroup;
import io.opaa.compliance.ComplianceManifest.SettingSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Objects;

/**
 * The disclosure of the data collection ("Auskunft über die Datenerhebung") of one running
 * instance: the {@link ComplianceManifest} of its version joined with the values the instance
 * actually uses. It holds no secret, no personal data and no content; a configuration value appears
 * only for a key the manifest lists under {@code settings}, and every value is passed through
 * {@link InstanceDisclosureAssembler#exportable(String)}.
 *
 * @param opaaVersion the version of the running backend, {@code 0.0.0-dev} outside a release
 * @param commit the commit the running image was built from, {@code unbekannt} when not known
 * @param generatedAt when the disclosure was assembled
 * @param settings every setting of the manifest, in manifest order, with its effective value
 * @param dataFlows every outbound data flow of the manifest with whether it is switched on
 * @param connectorTypes the connector types libraries or connection profiles use, by type
 * @param modelProviders the configured model endpoints of every model role
 * @param instances the running instances of the installation, the answering one included
 * @param protocol the description of the audit log for the staff council
 */
public record InstanceDisclosure(
    String opaaVersion,
    String commit,
    Instant generatedAt,
    ComplianceManifest manifest,
    List<SettingValue> settings,
    List<DataFlowState> dataFlows,
    List<ConnectorUsage> connectorTypes,
    List<ModelProvider> modelProviders,
    List<RunningInstance> instances,
    Protocol protocol) {

  public InstanceDisclosure {
    Objects.requireNonNull(opaaVersion, "opaaVersion");
    Objects.requireNonNull(commit, "commit");
    Objects.requireNonNull(generatedAt, "generatedAt");
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(protocol, "protocol");
    settings = List.copyOf(settings);
    dataFlows = List.copyOf(dataFlows);
    connectorTypes = List.copyOf(connectorTypes);
    modelProviders = List.copyOf(modelProviders);
    instances = List.copyOf(instances);
  }

  /** The settings whose effective value differs from the delivered default. */
  public List<SettingValue> deviations() {
    return settings.stream().filter(SettingValue::deviates).toList();
  }

  /**
   * One setting of the manifest in this instance.
   *
   * @param effectiveValue the value of the installation; {@code null} when it could not be
   *     determined or differs between the running instances
   * @param deviates whether {@code effectiveValue} differs from {@code defaultValue}
   * @param changedAt the last change of the settings row holding a {@code DATABASE} setting
   * @param sameOnAllInstances whether every running instance holds the same value
   */
  public record SettingValue(
      String area,
      String key,
      SettingSource source,
      String defaultValue,
      String effectiveValue,
      boolean deviates,
      Instant changedAt,
      String range,
      String effect,
      boolean sameOnAllInstances) {}

  /**
   * A running instance of the installation (ADR-0053, Entscheidung 3).
   *
   * @param version its version; two differ only during a rolling update
   * @param startedAt when it joined, by the database clock
   */
  public record RunningInstance(String version, Instant startedAt) {

    public RunningInstance {
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(startedAt, "startedAt");
    }
  }

  /**
   * The value a module holds for a {@code DATABASE} setting.
   *
   * @param changedAt the last change of the row holding it; {@code null} when the row keeps none
   */
  public record StoredValue(String value, Instant changedAt) {

    public StoredValue {
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * One outbound data flow of the manifest in this instance.
   *
   * @param active whether it is switched on; {@code null} where it happens only on an action of a
   *     person and no switch decides it
   */
  public record DataFlowState(String id, DataFlowGroup group, String target, Boolean active) {}

  /**
   * A connector type in use.
   *
   * @param type the source type key, such as {@code CONFLUENCE}
   * @param libraries how many knowledge libraries read from it
   * @param connectionProfiles how many connection profiles reach it
   */
  public record ConnectorUsage(String type, long libraries, long connectionProfiles) {}

  /**
   * A configured model endpoint.
   *
   * @param role {@code CHAT}, {@code EMBEDDING}, {@code RERANK} or {@code TRANSCRIPTION}
   * @param name the display name the administration gave it, where it has one
   * @param baseUrl the address; {@code null} when the role is unbound
   * @param active whether the role sends data to this endpoint
   */
  public record ModelProvider(
      String role, String name, String baseUrl, String model, boolean active) {}

  /**
   * The description of the audit log, not its content ("Auszug für die Personalvertretung"); the
   * event types with their purpose are the {@code auditEvents} of the manifest.
   *
   * @param fields every field of the protocol record
   * @param queryPaths the ways the log can be read
   * @param absentQueryPaths the ways that deliberately do not exist
   * @param networkAddress whether the network address is recorded
   * @param incidentScopeCases the anlassbezogenen Klärungen of the reporting period, by purpose
   */
  public record Protocol(
      List<ProtocolField> fields,
      List<QueryPath> queryPaths,
      List<String> absentQueryPaths,
      String networkAddress,
      IncidentScopeCases incidentScopeCases) {

    public Protocol {
      fields = List.copyOf(fields);
      queryPaths = List.copyOf(queryPaths);
      absentQueryPaths = List.copyOf(absentQueryPaths);
      Objects.requireNonNull(networkAddress, "networkAddress");
      Objects.requireNonNull(incidentScopeCases, "incidentScopeCases");
    }
  }

  /**
   * The reporting period of the disclosure: whole calendar months, because the cases of the
   * anlassbezogene Klärung are counted per month and a finer period would claim a precision the
   * counts do not have. The caller checks a period it was asked for and refuses it in words; this
   * record only holds the invariant.
   */
  public record ReportingPeriod(LocalDate from, LocalDate to) {

    public ReportingPeriod {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      if (from.getDayOfMonth() != 1
          || !to.equals(to.with(TemporalAdjusters.lastDayOfMonth()))
          || from.isAfter(to)) {
        throw new IllegalArgumentException(
            "a reporting period is whole calendar months, got " + from + " to " + to);
      }
    }
  }

  /**
   * How often the anlassbezogene Klärung was requested, approved and ended in a reporting period,
   * by purpose. It holds no person, no reason, no grant and no date finer than a month.
   *
   * @param purposes every purpose of the closed list, zero counts included
   * @param notes what the counts mean and where they end
   */
  public record IncidentScopeCases(
      ReportingPeriod period, List<PurposeCases> purposes, List<String> notes) {

    public IncidentScopeCases {
      Objects.requireNonNull(period, "period");
      purposes = List.copyOf(purposes);
      notes = List.copyOf(notes);
    }
  }

  /**
   * The counts of one purpose.
   *
   * @param purpose the purpose as its constant, such as {@code SECURITY_INCIDENT}
   * @param label the purpose in words
   * @param requested requests made in the period
   * @param approved approvals given in the period
   * @param expired approvals whose usability ended in the period, up to the time of the disclosure
   */
  public record PurposeCases(
      String purpose, String label, CaseCount requested, CaseCount approved, CaseCount expired) {}

  /**
   * One count as it may leave: zero and counts from {@link #THRESHOLD} on exactly, a count below it
   * only as "weniger als {@value #THRESHOLD}", so that a single case does not stand out in the
   * disclosure. {@code count} is {@code null} exactly when the count is below the threshold.
   */
  public record CaseCount(Long count) {

    /** The smallest count given exactly, zero aside. */
    public static final int THRESHOLD = 3;

    public CaseCount {
      if (count != null && (count < 0 || (count > 0 && count < THRESHOLD))) {
        throw new IllegalArgumentException("a count below the threshold is not given exactly");
      }
    }

    /** {@code raw} as it may leave. */
    public static CaseCount of(long raw) {
      if (raw < 0) {
        throw new IllegalArgumentException("a count is never negative: " + raw);
      }
      return new CaseCount(raw > 0 && raw < THRESHOLD ? null : raw);
    }

    public boolean belowThreshold() {
      return count == null;
    }
  }

  /** One field of the protocol record with its granularity, purpose and retention. */
  public record ProtocolField(String name, String granularity, String purpose, String retention) {}

  /** One way the protocol can be read. */
  public record QueryPath(String name, String description) {}
}
