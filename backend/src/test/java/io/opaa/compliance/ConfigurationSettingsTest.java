package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ConfigurationGuard.ApplicationYaml;
import io.opaa.compliance.ConfigurationGuard.HelmChart;
import io.opaa.compliance.ConfigurationGuard.Key;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The configuration side of the completeness guard on the delivered state (ADR-0052, Entscheidung
 * 9): metadata, markers, {@code settings} entries and the Helm chart in step. A changed default of
 * a key with data impact fails here until its entry follows; the message names the YAML to set.
 */
class ConfigurationSettingsTest {

  private static final ComplianceManifest MANIFEST = ComplianceManifestLoader.load();
  private static final List<Key> METADATA = ConfigurationGuard.deliveredMetadata();
  private static final ApplicationYaml YAML = ApplicationYaml.delivered();

  @Test
  void describesEveryOpaaKeyWithTheDeliveredDefault() {
    assertThat(METADATA).isNotEmpty();
    List<String> gaps = ConfigurationGuard.metadataGaps(METADATA, YAML);

    assertThat(gaps).withFailMessage(() -> ConfigurationGuard.report("metadata", gaps)).isEmpty();
  }

  @Test
  void classifiesEveryOpaaKey() {
    List<String> gaps = ConfigurationGuard.classificationGaps(METADATA);

    assertThat(gaps).withFailMessage(() -> ConfigurationGuard.report("markers", gaps)).isEmpty();
  }

  @Test
  void describesEveryKeyWithDataImpactWithItsCurrentDefault() {
    List<String> gaps = ConfigurationGuard.settingGaps(MANIFEST.settings(), METADATA, YAML);

    assertThat(gaps).withFailMessage(() -> ConfigurationGuard.report("defaults", gaps)).isEmpty();
  }

  @Test
  void namesTheHelmValueOfEveryKeyTheChartSets() {
    List<String> gaps =
        ConfigurationGuard.helmGaps(MANIFEST.settings(), YAML, HelmChart.delivered());

    assertThat(gaps).withFailMessage(() -> ConfigurationGuard.report("Helm chart", gaps)).isEmpty();
  }

  @Test
  void describesTheStandardModels() {
    assertThat(MANIFEST.settings())
        .extracting(ComplianceManifest.SettingEntry::key)
        .contains(
            "llm_models.model_identifier",
            "spring.ai.openai.embedding.model",
            "opaa.rerank.model",
            "opaa.transcription.model");
  }
}
