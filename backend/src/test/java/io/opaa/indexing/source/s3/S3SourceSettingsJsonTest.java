package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The stored JSON of {@link S3SourceSettings} round-trips and keeps its API shape. */
class S3SourceSettingsJsonTest {

  @Test
  void writesTheApiShapeAndReadsItBack() {
    S3SourceSettings settings =
        new S3SourceSettings(
            "eu-central-1",
            true,
            List.of(S3Scope.of("dokumente", "2025/"), S3Scope.of("archiv", "")),
            List.of("**/*.pdf"),
            List.of("**/~*"));

    String json = S3SourceSettingsJson.write(settings);

    assertThat(json)
        .isEqualTo(
            "{\"region\":\"eu-central-1\",\"pathStyle\":true,"
                + "\"scopes\":[{\"bucket\":\"dokumente\",\"prefix\":\"2025/\"},"
                + "{\"bucket\":\"archiv\",\"prefix\":\"\"}],"
                + "\"includePatterns\":[\"**/*.pdf\"],\"excludePatterns\":[\"**/~*\"]}");
    assertThat(S3SourceSettingsJson.read(json)).isEqualTo(settings);
  }

  @Test
  void aNullOrBlankColumnIsNoConfiguration() {
    assertThat(S3SourceSettingsJson.write(null)).isNull();
    assertThat(S3SourceSettingsJson.read(null)).isNull();
    assertThat(S3SourceSettingsJson.read(" ")).isNull();
  }

  @Test
  void aStoredDocumentIsValidatedOnTheWayBack() {
    assertThatThrownBy(() -> S3SourceSettingsJson.read("{\"scopes\":[]}"))
        .isInstanceOf(S3SourceSettings.InvalidS3SourceSettingsException.class);
    assertThatThrownBy(() -> S3SourceSettingsJson.read("kein json"))
        .isInstanceOf(S3SourceSettings.InvalidS3SourceSettingsException.class)
        .hasMessageContaining("JSON");
    // absent optional members read as their defaults
    S3SourceSettings minimal =
        S3SourceSettingsJson.read("{\"scopes\":[{\"bucket\":\"dokumente\"}]}");
    assertThat(minimal.region()).isNull();
    assertThat(minimal.pathStyle()).isFalse();
    assertThat(minimal.scopes()).containsExactly(S3Scope.of("dokumente", ""));
    assertThat(minimal.includePatterns()).isEmpty();
  }
}
