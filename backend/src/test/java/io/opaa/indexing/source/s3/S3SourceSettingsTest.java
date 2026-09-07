package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link S3SourceSettings} (ADR-0027, Entscheidungen 1 and 2): the record refuses what cannot be a
 * library's configuration - no scope, too many, overlapping ones, an unusable region or pattern -
 * and normalises the rest, so a stored document always reads back valid.
 */
class S3SourceSettingsTest {

  @Test
  void normalisesRegionScopesAndPatterns() {
    S3SourceSettings settings =
        new S3SourceSettings(
            " eu-central-1 ",
            true,
            List.of(S3Scope.of("dokumente", "/2025/protokolle")),
            List.of(" **/*.pdf "),
            null);

    assertThat(settings.region()).isEqualTo("eu-central-1");
    assertThat(settings.effectiveRegion()).isEqualTo("eu-central-1");
    assertThat(settings.pathStyle()).isTrue();
    assertThat(settings.scopes()).containsExactly(S3Scope.of("dokumente", "2025/protokolle/"));
    assertThat(settings.includePatterns()).containsExactly("**/*.pdf");
    assertThat(settings.excludePatterns()).isEmpty();
  }

  @Test
  void aBlankRegionMeansTheDefault() {
    S3SourceSettings settings =
        new S3SourceSettings("  ", false, List.of(S3Scope.of("dokumente", "")), null, null);

    assertThat(settings.region()).isNull();
    assertThat(settings.effectiveRegion()).isEqualTo(S3Connection.DEFAULT_REGION);
  }

  @Test
  void needsAtLeastOneScopeAndRefusesOverlapAndExcess() {
    assertThatThrownBy(() -> new S3SourceSettings(null, true, List.of(), null, null))
        .isInstanceOf(S3SourceSettings.InvalidS3SourceSettingsException.class)
        .hasMessageContaining("Mindestens ein Geltungsbereich");
    assertThatThrownBy(() -> new S3SourceSettings(null, true, null, null, null))
        .isInstanceOf(S3SourceSettings.InvalidS3SourceSettingsException.class)
        .hasMessageContaining("Mindestens ein Geltungsbereich");
    assertThatThrownBy(
            () ->
                new S3SourceSettings(
                    null,
                    true,
                    List.of(S3Scope.of("dokumente", "2025"), S3Scope.of("dokumente", "2025/q1")),
                    null,
                    null))
        .isInstanceOf(S3SourceSettings.InvalidS3SourceSettingsException.class)
        .hasMessageContaining("dokumente/2025/")
        .hasMessageContaining("dokumente/2025/q1/")
        .hasMessageContaining("überschneiden");
    List<S3Scope> tooMany =
        Collections.nCopies(S3Scope.MAX_PER_LIBRARY + 1, S3Scope.of("dokumente", "")).stream()
            .map(scope -> scope)
            .toList();
    assertThatThrownBy(() -> new S3SourceSettings(null, true, tooMany, null, null))
        .isInstanceOf(S3SourceSettings.InvalidS3SourceSettingsException.class)
        .hasMessageContaining("Höchstens " + S3Scope.MAX_PER_LIBRARY);
  }

  @Test
  void refusesAnUnusableRegionOrPattern() {
    List<S3Scope> scope = List.of(S3Scope.of("dokumente", ""));
    assertThatThrownBy(() -> new S3SourceSettings("eu central", true, scope, null, null))
        .isInstanceOf(S3SourceSettings.InvalidS3SourceSettingsException.class)
        .hasMessageContaining("Region");
    assertThatThrownBy(() -> new S3SourceSettings(null, true, scope, List.of(""), null))
        .isInstanceOf(S3SourceSettings.InvalidS3SourceSettingsException.class)
        .hasMessageContaining("Einschlussmuster");
    assertThatThrownBy(() -> new S3SourceSettings(null, true, scope, null, List.of("**/[")))
        .isInstanceOf(S3SourceSettings.InvalidS3SourceSettingsException.class)
        .hasMessageContaining("Ausschlussmuster")
        .hasMessageContaining("Glob");
    assertThatThrownBy(
            () -> new S3SourceSettings(null, true, scope, List.of("*.pdf", "*.pdf"), null))
        .isInstanceOf(S3SourceSettings.InvalidS3SourceSettingsException.class)
        .hasMessageContaining("mehrfach");
    assertThatThrownBy(
            () -> new S3SourceSettings(null, true, scope, Collections.nCopies(51, "*.pdf"), null))
        .isInstanceOf(S3SourceSettings.InvalidS3SourceSettingsException.class)
        .hasMessageContaining("Höchstens 50");
  }
}
