package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ComplianceManifest.SettingSource;
import io.opaa.compliance.InstanceDisclosure.ConnectorUsage;
import io.opaa.compliance.InstanceDisclosure.DataFlowState;
import io.opaa.compliance.InstanceDisclosure.IncidentScopeCases;
import io.opaa.compliance.InstanceDisclosure.ModelProvider;
import io.opaa.compliance.InstanceDisclosure.Protocol;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import io.opaa.compliance.InstanceDisclosure.SettingValue;
import io.opaa.compliance.InstanceDisclosure.StoredValue;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The disclosure of one instance assembled from the shipped manifest, the environment and the
 * modules' {@link InstanceStateSource}s: exactly the deviating settings, values only for keys the
 * manifest lists, never a secret, and addresses without user information, query or fragment.
 */
class InstanceDisclosureAssemblerTest {

  private static final ComplianceManifest MANIFEST = ComplianceManifestLoader.load();
  private static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID ORGANIZATION = UUID.randomUUID();
  private static final Protocol PROTOCOL =
      new Protocol(
          List.of(),
          List.of(),
          List.of(),
          "nein",
          new IncidentScopeCases(
              new ReportingPeriod(LocalDate.parse("2025-01-01"), LocalDate.parse("2026-12-31")),
              List.of(),
              List.of()));

  private static final String API_KEY = "sk-S3CR3T-API-KEY-4711";
  private static final String PASSWORD = "S3CR3T-PASSWORD-0815";
  private static final String URL_PASSWORD = "S3CR3T-URL-PASSWORD";
  private static final String URL_QUERY_TOKEN = "S3CR3T-QUERY-TOKEN";

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(MapperFeature.INFER_RECORD_GETTERS_FROM_COMPONENTS_ONLY).build();

  @Test
  void namesExactlyTheSettingsThatDeviateFromTheirDefault() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("opaa.rerank.enabled", "true")
            .withProperty("opaa.rerank.model", defaultOf("opaa.rerank.model"))
            .withProperty("opaa.demo.chat-import.enabled", "FALSE");
    InstanceStateSource audit =
        new InstanceStateSource() {
          @Override
          public Map<String, StoredValue> storedSettings() {
            return Map.of(
                "audit_retention_settings.retention_months",
                new StoredValue("24", Instant.parse("2026-10-01T10:00:00Z")));
          }
        };

    InstanceDisclosure disclosure =
        assembler(environment, List.of(audit)).assemble(MANIFEST, PROTOCOL, ORGANIZATION);

    assertThat(disclosure.deviations())
        .extracting(SettingValue::key)
        .containsExactlyInAnyOrder(
            "opaa.rerank.enabled", "audit_retention_settings.retention_months");
    SettingValue retention = setting(disclosure, "audit_retention_settings.retention_months");
    assertThat(retention.source()).isEqualTo(SettingSource.DATABASE);
    assertThat(retention.defaultValue()).isEqualTo("36");
    assertThat(retention.effectiveValue()).isEqualTo("24");
    assertThat(retention.changedAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
  }

  @Test
  void leavesAValueItCannotDetermineOpenInsteadOfCallingItADeviation() {
    InstanceDisclosure disclosure =
        assembler(new MockEnvironment(), List.of()).assemble(MANIFEST, PROTOCOL, ORGANIZATION);

    SettingValue retention = setting(disclosure, "audit_retention_settings.retention_months");
    assertThat(retention.effectiveValue()).isNull();
    assertThat(retention.deviates()).isFalse();
    SettingValue rerank = setting(disclosure, "opaa.rerank.enabled");
    assertThat(rerank.effectiveValue()).isNull();
    assertThat(disclosure.deviations()).isEmpty();
  }

  @Test
  void aConstantIsAlwaysItsDefault() {
    InstanceDisclosure disclosure =
        assembler(new MockEnvironment(), List.of()).assemble(MANIFEST, PROTOCOL, ORGANIZATION);

    SettingValue window =
        setting(disclosure, "io.opaa.audit.AuditIncidentScopeGrant#USABLE_WINDOW");
    assertThat(window.effectiveValue()).isEqualTo(window.defaultValue());
    assertThat(window.deviates()).isFalse();
  }

  @Test
  void exportsNoSecretAndNoValueOfAKeyTheManifestDoesNotList() throws Exception {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", API_KEY)
            .withProperty("spring.ai.openai.chat.api-key", API_KEY)
            .withProperty("opaa.rerank.api-key", API_KEY)
            .withProperty("spring.datasource.password", PASSWORD)
            .withProperty("opaa.upload.s3.secret-key", PASSWORD)
            .withProperty(
                "spring.ai.openai.chat.base-url",
                "https://nutzer:"
                    + URL_PASSWORD
                    + "@llm.example:8443/v1?key="
                    + URL_QUERY_TOKEN
                    + "#frag");
    InstanceStateSource mail =
        new InstanceStateSource() {
          @Override
          public Map<String, StoredValue> storedSettings() {
            return Map.of("mail_settings.password", new StoredValue(PASSWORD, null));
          }

          @Override
          public List<ModelProvider> modelProviders() {
            return List.of(
                new ModelProvider(
                    "CHAT",
                    "Hausmodell",
                    "https://key:" + URL_PASSWORD + "@models.example/v1?token=" + URL_QUERY_TOKEN,
                    "llama",
                    true));
          }
        };

    InstanceDisclosure disclosure =
        assembler(environment, List.of(mail)).assemble(MANIFEST, PROTOCOL, ORGANIZATION);

    String json = JSON.writeValueAsString(disclosure);
    String markdown = InstanceDisclosureRenderer.render(disclosure);
    for (String secret : List.of(API_KEY, PASSWORD, URL_PASSWORD, URL_QUERY_TOKEN)) {
      assertThat(json).as("JSON form").doesNotContain(secret);
      assertThat(markdown).as("Markdown form").doesNotContain(secret);
    }
    assertThat(json).doesNotContain("mail_settings.password").doesNotContain("api-key\"");
    assertThat(setting(disclosure, "spring.ai.openai.chat.base-url").effectiveValue())
        .isEqualTo("https://llm.example:8443/v1");
    assertThat(disclosure.modelProviders())
        .extracting(ModelProvider::baseUrl)
        .containsExactly("https://models.example/v1");
  }

  @Test
  void stripsUserInformationQueryAndFragmentFromEveryAddress() {
    assertThat(InstanceDisclosureAssembler.exportable("https://u:p@host.example/path?q=1#x"))
        .isEqualTo("https://host.example/path");
    assertThat(InstanceDisclosureAssembler.exportable("http://localhost:11434/v1"))
        .isEqualTo("http://localhost:11434/v1");
    assertThat(InstanceDisclosureAssembler.exportable("/srv/a,https://u:p@b.example/x?y=z,/srv/c"))
        .isEqualTo("/srv/a,https://b.example/x,/srv/c");
    assertThat(InstanceDisclosureAssembler.exportable("not a uri://@ %%"))
        .doesNotContain("@")
        .isEqualTo(InstanceDisclosureAssembler.UNREADABLE_ADDRESS);
    assertThat(InstanceDisclosureAssembler.exportable("7d")).isEqualTo("7d");
  }

  @Test
  void aCommaInsideUserInformationLeaksNoPartOfTheAddress() {
    assertThat(InstanceDisclosureAssembler.exportable("https://u:abc,SECRETTAIL@llm.example/v1"))
        .isEqualTo(InstanceDisclosureAssembler.UNREADABLE_ADDRESS);
    assertThat(InstanceDisclosureAssembler.exportable("https://ok.example/v1,not a uri://x y"))
        .isEqualTo(InstanceDisclosureAssembler.UNREADABLE_ADDRESS);
    assertThat(InstanceDisclosureAssembler.exportable("https://ok.example/v1,user@host"))
        .isEqualTo(InstanceDisclosureAssembler.UNREADABLE_ADDRESS);
  }

  @Test
  void aFlowIsOnWhenAnySourceSwitchesItOnAndOpenWhenNoneDecides() {
    InstanceStateSource libraries =
        new InstanceStateSource() {
          @Override
          public Map<String, Boolean> dataFlows(UUID organizationId) {
            return Map.of("source-web", true, "source-rss", false);
          }
        };
    InstanceStateSource other =
        new InstanceStateSource() {
          @Override
          public Map<String, Boolean> dataFlows(UUID organizationId) {
            return Map.of("source-web", false);
          }
        };

    InstanceDisclosure disclosure =
        assembler(new MockEnvironment(), List.of(libraries, other))
            .assemble(MANIFEST, PROTOCOL, ORGANIZATION);

    assertThat(flow(disclosure, "source-web").active()).isTrue();
    assertThat(flow(disclosure, "source-rss").active()).isFalse();
    assertThat(flow(disclosure, "model-endpoint").active()).isNull();
    assertThat(disclosure.dataFlows())
        .extracting(DataFlowState::id)
        .containsExactlyElementsOf(
            MANIFEST.dataFlows().stream().map(ComplianceManifest.DataFlowEntry::id).toList());
  }

  @Test
  void addsUpTheConnectorUsageOfAllSourcesByType() {
    InstanceStateSource libraries =
        new InstanceStateSource() {
          @Override
          public List<ConnectorUsage> connectorUsage(UUID organizationId) {
            return List.of(new ConnectorUsage("HTTP_DIRECTORY", 2, 0));
          }
        };
    InstanceStateSource profiles =
        new InstanceStateSource() {
          @Override
          public List<ConnectorUsage> connectorUsage(UUID organizationId) {
            return List.of(
                new ConnectorUsage("HTTP_DIRECTORY", 0, 1), new ConnectorUsage("CONFLUENCE", 0, 1));
          }
        };

    InstanceDisclosure disclosure =
        assembler(new MockEnvironment(), List.of(libraries, profiles))
            .assemble(MANIFEST, PROTOCOL, ORGANIZATION);

    assertThat(disclosure.connectorTypes())
        .containsExactly(
            new ConnectorUsage("CONFLUENCE", 0, 1), new ConnectorUsage("HTTP_DIRECTORY", 2, 1));
  }

  @Test
  void carriesVersionCommitAndTheTimeOfAssembly() {
    InstanceDisclosure withCommit =
        new InstanceDisclosureAssembler(
                new MockEnvironment().withProperty("opaa.build.commit", "abc123"),
                "1.4.0",
                List.of(),
                CLOCK)
            .assemble(MANIFEST, PROTOCOL, ORGANIZATION);
    InstanceDisclosure withoutCommit =
        assembler(new MockEnvironment(), List.of()).assemble(MANIFEST, PROTOCOL, ORGANIZATION);

    assertThat(withCommit.opaaVersion()).isEqualTo("1.4.0");
    assertThat(withCommit.commit()).isEqualTo("abc123");
    assertThat(withCommit.generatedAt()).isEqualTo(NOW);
    assertThat(withoutCommit.commit()).isEqualTo(InstanceDisclosureAssembler.UNKNOWN_COMMIT);
  }

  private static InstanceDisclosureAssembler assembler(
      MockEnvironment environment, List<InstanceStateSource> sources) {
    return new InstanceDisclosureAssembler(environment, "0.0.0-dev", sources, CLOCK);
  }

  private static String defaultOf(String key) {
    return MANIFEST.settings().stream()
        .filter(entry -> entry.key().equals(key))
        .findFirst()
        .orElseThrow()
        .defaultValue();
  }

  private static SettingValue setting(InstanceDisclosure disclosure, String key) {
    return disclosure.settings().stream()
        .filter(value -> value.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no setting " + key));
  }

  private static DataFlowState flow(InstanceDisclosure disclosure, String id) {
    return disclosure.dataFlows().stream()
        .filter(state -> state.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no data flow " + id));
  }
}
