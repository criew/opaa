package io.opaa.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.DataCollectionDisclosure;
import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.api.types.ChatCleanupHoldPurpose;
import io.opaa.compliance.ComplianceManifest.DataFlowGroup;
import io.opaa.compliance.ComplianceManifest.SettingSource;
import io.opaa.compliance.ComplianceManifestLoader;
import io.opaa.compliance.FactSheetRenderer;
import io.opaa.compliance.InstanceDisclosure;
import io.opaa.compliance.InstanceDisclosure.CaseCount;
import io.opaa.compliance.InstanceDisclosure.ConnectorUsage;
import io.opaa.compliance.InstanceDisclosure.DataFlowState;
import io.opaa.compliance.InstanceDisclosure.HoldPurposeCases;
import io.opaa.compliance.InstanceDisclosure.IncidentScopeCases;
import io.opaa.compliance.InstanceDisclosure.ModelProvider;
import io.opaa.compliance.InstanceDisclosure.Protocol;
import io.opaa.compliance.InstanceDisclosure.ProtocolField;
import io.opaa.compliance.InstanceDisclosure.PurposeCases;
import io.opaa.compliance.InstanceDisclosure.QueryPath;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import io.opaa.compliance.InstanceDisclosure.RunningInstance;
import io.opaa.compliance.InstanceDisclosure.SettingValue;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every field of the disclosure reaches its place in the response. */
class DataCollectionDisclosureResponseMapperTest {

  private static final Instant GENERATED = Instant.parse("2026-10-17T08:00:00Z");
  private static final Instant CHANGED = Instant.parse("2026-10-01T10:00:00Z");
  private static final LocalDate FROM = LocalDate.parse("2025-01-01");
  private static final LocalDate TO = LocalDate.parse("2026-12-31");
  private static final Instant STARTED = Instant.parse("2026-10-16T06:00:00Z");

  @Test
  void mapsEveryField() {
    InstanceDisclosure disclosure =
        new InstanceDisclosure(
            "1.4.0",
            "abc123",
            GENERATED,
            ComplianceManifestLoader.load(),
            List.of(
                new SettingValue(
                    "audit-log",
                    "audit_retention_settings.retention_months",
                    SettingSource.DATABASE,
                    "36",
                    "24",
                    true,
                    CHANGED,
                    "12 bis 120 Monate",
                    "Frist",
                    true)),
            List.of(new DataFlowState("model-endpoint", DataFlowGroup.MODEL, "Formular", null)),
            List.of(new ConnectorUsage("CONFLUENCE", 2, 1)),
            List.of(new ModelProvider("CHAT", "Hausmodell", "https://llm.example/v1", "m", true)),
            List.of(new RunningInstance("1.4.0", STARTED)),
            new Protocol(
                List.of(new ProtocolField("recorded_at", "Mikrosekunde", "Zeitachse", "24")),
                List.of(new QueryPath("nach Objekt", "Objekt")),
                List.of("kein Personenfilter"),
                "nein",
                new IncidentScopeCases(
                    new ReportingPeriod(FROM, TO),
                    List.of(
                        new PurposeCases(
                            "DATA_BREACH_INVESTIGATION",
                            "Untersuchung einer Datenschutzverletzung",
                            CaseCount.of(5),
                            CaseCount.of(2),
                            CaseCount.of(0))),
                    CaseCount.of(1),
                    List.of(
                        new HoldPurposeCases(
                            "DATA_SUBJECT_REQUEST",
                            "Laufender Auskunfts- oder Löschantrag",
                            CaseCount.of(3),
                            CaseCount.of(0),
                            CaseCount.of(0),
                            CaseCount.of(1),
                            CaseCount.of(0))),
                    List.of("Eine Ablehnung gibt es nicht"))));

    DataCollectionDisclosure response =
        DataCollectionDisclosureResponseMapper.toResponse(disclosure);

    assertThat(response.getOpaaVersion()).isEqualTo("1.4.0");
    assertThat(response.getCommit()).isEqualTo("abc123");
    assertThat(response.getGeneratedAt()).isEqualTo(GENERATED);
    assertThat(response.getNotice()).isEqualTo(FactSheetRenderer.NOTICE);
    assertThat(response.getManifest()).containsKeys("auditEvents", "settings", "dataFlows");
    var setting = response.getInstance().getSettings().get(0);
    assertThat(setting.getKey()).isEqualTo("audit_retention_settings.retention_months");
    assertThat(setting.getArea()).isEqualTo("audit-log");
    assertThat(setting.getSource()).isEqualTo("DATABASE");
    assertThat(setting.getDefaultValue()).isEqualTo("36");
    assertThat(setting.getEffectiveValue()).isEqualTo("24");
    assertThat(setting.getDeviates()).isTrue();
    assertThat(setting.getChangedAt()).isEqualTo(CHANGED);
    assertThat(setting.getRange()).isEqualTo("12 bis 120 Monate");
    assertThat(setting.getEffect()).isEqualTo("Frist");
    assertThat(setting.getSameOnAllInstances()).isTrue();
    var instance = response.getInstance().getInstances().get(0);
    assertThat(instance.getVersion()).isEqualTo("1.4.0");
    assertThat(instance.getStartedAt()).isEqualTo(STARTED);
    var flow = response.getInstance().getDataFlows().get(0);
    assertThat(flow.getId()).isEqualTo("model-endpoint");
    assertThat(flow.getGroup()).isEqualTo("MODEL");
    assertThat(flow.getTarget()).isEqualTo("Formular");
    assertThat(flow.getActive()).isNull();
    var connector = response.getInstance().getConnectorTypes().get(0);
    assertThat(connector.getType()).isEqualTo("CONFLUENCE");
    assertThat(connector.getLibraries()).isEqualTo(2);
    assertThat(connector.getConnectionProfiles()).isEqualTo(1);
    var provider = response.getInstance().getModelProviders().get(0);
    assertThat(provider.getRole()).isEqualTo("CHAT");
    assertThat(provider.getName()).isEqualTo("Hausmodell");
    assertThat(provider.getBaseUrl()).isEqualTo("https://llm.example/v1");
    assertThat(provider.getModel()).isEqualTo("m");
    assertThat(provider.getActive()).isTrue();
    var field = response.getProtocol().getFields().get(0);
    assertThat(field.getName()).isEqualTo("recorded_at");
    assertThat(field.getGranularity()).isEqualTo("Mikrosekunde");
    assertThat(field.getPurpose()).isEqualTo("Zeitachse");
    assertThat(field.getRetention()).isEqualTo("24");
    assertThat(response.getProtocol().getQueryPaths().get(0).getName()).isEqualTo("nach Objekt");
    assertThat(response.getProtocol().getQueryPaths().get(0).getDescription()).isEqualTo("Objekt");
    assertThat(response.getProtocol().getAbsentQueryPaths()).containsExactly("kein Personenfilter");
    assertThat(response.getProtocol().getNetworkAddress()).isEqualTo("nein");
    var cases = response.getProtocol().getIncidentScopeCases();
    assertThat(cases.getFrom()).isEqualTo(FROM);
    assertThat(cases.getTo()).isEqualTo(TO);
    assertThat(cases.getSmallCountThreshold()).isEqualTo(CaseCount.THRESHOLD);
    assertThat(cases.getNotes()).containsExactly("Eine Ablehnung gibt es nicht");
    assertThat(cases.getHeldChatDeletions().getBelowThreshold()).isTrue();
    assertThat(cases.getHeldChatDeletions().getCount()).isNull();
    assertThat(cases.getChatDeletionHolds())
        .singleElement()
        .satisfies(
            hold -> {
              assertThat(hold.getPurpose()).isEqualTo(ChatCleanupHoldPurpose.DATA_SUBJECT_REQUEST);
              assertThat(hold.getRequested().getCount()).isEqualTo(3);
              assertThat(hold.getRejected().getBelowThreshold()).isTrue();
            });
    var purpose = cases.getPurposes().get(0);
    assertThat(purpose.getPurpose()).isEqualTo(AuditIncidentScopePurpose.DATA_BREACH_INVESTIGATION);
    assertThat(purpose.getLabel()).isEqualTo("Untersuchung einer Datenschutzverletzung");
    assertThat(purpose.getRequested().getCount()).isEqualTo(5);
    assertThat(purpose.getRequested().getBelowThreshold()).isFalse();
    assertThat(purpose.getApproved().getCount()).isNull();
    assertThat(purpose.getApproved().getBelowThreshold()).isTrue();
    assertThat(purpose.getExpired().getCount()).isZero();
    assertThat(purpose.getExpired().getBelowThreshold()).isFalse();
  }
}
