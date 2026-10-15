package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ComplianceManifest.Bearing;
import io.opaa.compliance.ComplianceManifest.HelmValue;
import io.opaa.compliance.ComplianceManifest.SettingEntry;
import io.opaa.compliance.ComplianceManifest.SettingSource;
import io.opaa.compliance.ConfigurationGuard.ApplicationYaml;
import io.opaa.compliance.ConfigurationGuard.HelmChart;
import io.opaa.compliance.ConfigurationGuard.Impact;
import io.opaa.compliance.ConfigurationGuard.Key;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;

/** The rules of {@link ConfigurationGuard}, against fixture records, YAML and a fixture chart. */
class ConfigurationGuardTest {

  private static final ApplicationYaml YAML =
      ApplicationYaml.read(
          new ClassPathResource("compliance-fixtures/configuration/application.yml"));
  private static final HelmChart CHART =
      HelmChart.read(Path.of("src/test/resources/compliance-fixtures/configuration/helm"));

  @ConfigurationProperties("opaa.fixture")
  @NoDataImpact("fixture bounds")
  record FixtureProperties(
      @DataImpact int keepDays,
      int pageSize,
      boolean enabled,
      @NoDataImpact("fixture cache") Duration cacheTtl,
      @DataImpact @NoDataImpact("contradiction") int both) {}

  @ConfigurationProperties("opaa.unmarked")
  record UnmarkedProperties(int size) {}

  private static final List<Key> KEYS =
      List.of(key("opaa.fixture.keep-days", "30"), key("opaa.fixture.page-size", "100"));

  @Test
  void decidesAKeyByItsComponentFirstThenByItsType() {
    assertThat(impact("opaa.fixture.keep-days")).isEqualTo(Impact.DATA);
    assertThat(impact("opaa.fixture.page-size")).isEqualTo(Impact.NONE);
    assertThat(impact("opaa.fixture.cache-ttl")).isEqualTo(Impact.NONE);
  }

  @Test
  void aBlanketOnAPropertiesTypeDoesNotCoverASwitchModelAddressOrPeriod() {
    List<String> gaps = new ArrayList<>();

    assertThat(ConfigurationGuard.impactOf(key("opaa.fixture.enabled", null), gaps)).isEmpty();
    assertThat(gaps)
        .singleElement()
        .asString()
        .contains("decided on its own", "'enabled'", FixtureProperties.class.getName());
    assertThat(ConfigurationGuard.needsOwnDecision("opaa.x.base-url")).isTrue();
    assertThat(ConfigurationGuard.needsOwnDecision("opaa.x.delete-after-days")).isTrue();
    assertThat(ConfigurationGuard.needsOwnDecision("opaa.x.access-token-ttl")).isTrue();
    assertThat(ConfigurationGuard.needsOwnDecision("opaa.x.page-size")).isFalse();
  }

  @Test
  void reportsAnUnmarkedKeyAndContradictoryMarkers() {
    List<String> gaps =
        ConfigurationGuard.classificationGaps(
            List.of(
                new Key("opaa.unmarked.size", UnmarkedProperties.class.getName(), "d", null),
                key("opaa.fixture.both", null),
                new Key("opaa.nowhere", null, "d", null)));

    assertThat(gaps).hasSize(3);
    assertThat(gaps.get(0)).contains("opaa.unmarked.size: not classified", "@NoDataImpact");
    assertThat(gaps.get(1)).contains("both @DataImpact and @NoDataImpact");
    assertThat(gaps.get(2)).contains("no sourceType", "additional-spring-configuration-metadata");
  }

  @Test
  void acceptsAnEntryWithTheDeliveredDefaultAndTheProductionProfileNamed() {
    List<String> gaps =
        ConfigurationGuard.settingGaps(
            List.of(entry("opaa.fixture.keep-days", "30", "Profil docker: 60"), modelEntry()),
            KEYS,
            YAML);

    assertThat(gaps).isEmpty();
  }

  @Test
  void aChangedDefaultFailsWithTheValueToSet() {
    List<String> gaps =
        ConfigurationGuard.settingGaps(
            List.of(entry("opaa.fixture.keep-days", "14", "Profil docker: 60"), modelEntry()),
            KEYS,
            YAML);

    assertThat(gaps)
        .singleElement()
        .asString()
        .contains(
            "opaa.fixture.keep-days: application.yml now delivers \"30\"",
            "compliance/manifest/configuration.yaml still says \"14\"",
            "defaultValue: \"30\"");
  }

  @Test
  void aKeyWithDataImpactWithoutEntryFailsWithTheEntryToAdd() {
    List<String> gaps = ConfigurationGuard.settingGaps(List.of(), KEYS, YAML);

    assertThat(gaps).hasSize(2);
    assertThat(gaps.get(0))
        .contains(
            "opaa.fixture.keep-days: has data impact but no settings entry",
            "  - key: opaa.fixture.keep-days\n    source: PROPERTY\n    defaultValue: \"30\"");
    assertThat(gaps.get(1)).startsWith("spring.ai.openai.chat.model: has data impact");
  }

  @Test
  void aProductionProfileOverrideNeedsANoteButTheDevProfileDoesNot() {
    List<String> gaps =
        ConfigurationGuard.settingGaps(
            List.of(entry("opaa.fixture.keep-days", "30", null), modelEntry()), KEYS, YAML);

    assertThat(gaps).singleElement().asString().contains("'docker'", "\"Profil docker: 60\"");
  }

  @Test
  void anEntryForAKeyWithoutDataImpactOrWithoutKeyFails() {
    List<String> gaps =
        ConfigurationGuard.settingGaps(
            List.of(
                entry("opaa.fixture.keep-days", "30", "Profil docker: 60"),
                modelEntry(),
                entry("opaa.fixture.page-size", "100", null),
                entry("opaa.gone", "1", null)),
            KEYS,
            YAML);

    assertThat(gaps).hasSize(2);
    assertThat(gaps.get(0)).contains("opaa.fixture.page-size", "not marked @DataImpact");
    assertThat(gaps.get(1)).contains("opaa.gone", "does not set");
  }

  @Test
  void reportsAKeyWithoutMetadataWithoutDescriptionOrWithAStaleDefault() {
    List<String> described =
        ConfigurationGuard.metadataGaps(
            List.of(
                key("opaa.fixture.keep-days", "29"),
                new Key("opaa.fixture.page-size", FixtureProperties.class.getName(), "", "100")),
            YAML);
    List<String> undescribed =
        ConfigurationGuard.metadataGaps(List.of(key("opaa.fixture.page-size", "100")), YAML);

    assertThat(described).hasSize(2);
    assertThat(described.get(0))
        .contains("keep-days: the metadata says default \"29\", application.yml delivers \"30\"");
    assertThat(described.get(1)).contains("page-size: no description");
    assertThat(undescribed)
        .singleElement()
        .asString()
        .contains("opaa.fixture.keep-days: set in application.yml but not in the configuration");
  }

  @Test
  void theChartMustNameItsValueWithTheCurrentDefault() {
    List<String> missing =
        ConfigurationGuard.helmGaps(
            List.of(entry("opaa.fixture.keep-days", "30", null)), YAML, CHART);
    List<String> stale =
        ConfigurationGuard.helmGaps(
            List.of(entryWithHelm(new HelmValue("retention.keepDays", "14"))), YAML, CHART);
    List<String> wrongPath =
        ConfigurationGuard.helmGaps(
            List.of(entryWithHelm(new HelmValue("retention.keep", "30"))), YAML, CHART);
    List<String> current =
        ConfigurationGuard.helmGaps(
            List.of(entryWithHelm(new HelmValue("retention.keepDays", "30"))), YAML, CHART);

    assertThat(missing).hasSize(2);
    assertThat(missing.get(0)).contains("OPAA_NOT_READ", "no key of application.yml reads it");
    assertThat(missing.get(1)).contains("[OPAA_FIXTURE_KEEP_DAYS]", "values: <dotted path>");
    assertThat(stale.get(1))
        .contains("now defaults 'retention.keepDays' to \"30\"", "defaultValue: \"30\"");
    assertThat(wrongPath.get(1)).contains("has no value 'retention.keep'");
    assertThat(current).singleElement().asString().contains("OPAA_NOT_READ");
  }

  @Test
  void readsAnEmptyListOfTheChartAsAnEmptyDefault() {
    assertThat(CHART.defaultAt("sources")).contains("");
    assertThat(CHART.defaultAt("retention.keepDays")).contains("30");
    assertThat(CHART.defaultAt("missing")).isEmpty();
  }

  private static Impact impact(String name) {
    List<String> gaps = new ArrayList<>();
    Impact impact = ConfigurationGuard.impactOf(key(name, null), gaps).orElse(null);
    assertThat(gaps).isEmpty();
    return impact;
  }

  private static Key key(String name, String defaultValue) {
    return new Key(name, FixtureProperties.class.getName(), "described", defaultValue);
  }

  private static SettingEntry entry(String key, String defaultValue, String note) {
    return new SettingEntry(
        ConfigurationGuard.CONFIGURATION_AREA,
        key,
        SettingSource.PROPERTY,
        defaultValue,
        null,
        "Wirkung",
        List.of(Bearing.DATA),
        null,
        note == null ? null : List.of(note));
  }

  private static SettingEntry entryWithHelm(HelmValue helm) {
    return new SettingEntry(
        ConfigurationGuard.CONFIGURATION_AREA,
        "opaa.fixture.keep-days",
        SettingSource.PROPERTY,
        "30",
        null,
        "Wirkung",
        List.of(Bearing.DATA),
        helm,
        List.of("Profil docker: 60"));
  }

  private static SettingEntry modelEntry() {
    return new SettingEntry(
        ConfigurationGuard.CONFIGURATION_AREA,
        "spring.ai.openai.chat.model",
        SettingSource.PROPERTY,
        "small-model",
        null,
        "Standardmodell",
        List.of(Bearing.DATA_FLOW),
        null,
        null);
  }
}
