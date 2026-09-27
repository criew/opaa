package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The flat connector fields as the settings of one type, and the order in which a field of another
 * type is refused: edition and S3 settings (and on a change the spaces) before the connector
 * validates, every other one right after it.
 */
class FlatSourceSettingsTest {

  private static final ConnectorData S3 = ConnectorData.of(Map.of("scopes", List.of()));

  @Test
  void theConfluenceFieldsAreTheSettingsOfAConfluenceLibraryOnly() {
    FlatSourceSettings flat =
        new FlatSourceSettings(
            ConfluenceEdition.CLOUD, List.of(FlatSourceSettings.space("ENG", "Eng")), 7, null);

    assertThat(flat.addressedTo(DocumentSourceType.CONFLUENCE, false).asMap())
        .containsEntry("edition", "CLOUD")
        .containsEntry("fullSyncIntervalDays", 7)
        .containsKey("spaces");
    flat.rejectForeign(DocumentSourceType.CONFLUENCE, false);
    assertThat(
            new FlatSourceSettings(null, null, null, S3).addressedTo(DocumentSourceType.S3, true))
        .isEqualTo(S3);
    assertThat(
            new FlatSourceSettings(null, null, null, null)
                .addressedTo(DocumentSourceType.CONFLUENCE, true))
        .isNull();
  }

  @Test
  void anEditionOrS3SettingsOfAnotherTypeAreRefusedBeforeTheConnector() {
    assertThatThrownBy(
            () ->
                new FlatSourceSettings(ConfluenceEdition.CLOUD, null, null, null)
                    .addressedTo(DocumentSourceType.RSS_FEED, false))
        .isInstanceOf(ValidationException.class)
        .hasMessage("confluenceEdition ist nur für sourceType CONFLUENCE zulässig");
    assertThatThrownBy(
            () ->
                new FlatSourceSettings(null, null, null, S3)
                    .addressedTo(DocumentSourceType.CONFLUENCE, false))
        .isInstanceOf(ValidationException.class)
        .hasMessage("s3Settings sind nur für sourceType S3 zulässig");
  }

  @Test
  void foreignSpacesWaitForTheConnectorOnCreationButNotOnAChange() {
    FlatSourceSettings spaces =
        new FlatSourceSettings(null, List.of(FlatSourceSettings.space("A", null)), null, null);

    assertThat(spaces.addressedTo(DocumentSourceType.RSS_FEED, false)).isNull();
    assertThatThrownBy(() -> spaces.rejectForeign(DocumentSourceType.RSS_FEED, false))
        .isInstanceOf(ValidationException.class)
        .hasMessage("confluenceSpaces sind nur für sourceType CONFLUENCE zulässig");
    assertThatThrownBy(() -> spaces.addressedTo(DocumentSourceType.RSS_FEED, true))
        .isInstanceOf(ValidationException.class)
        .hasMessage("confluenceSpaces sind nur für sourceType CONFLUENCE zulässig");
  }

  @Test
  void aForeignRhythmIsAlwaysRefusedAfterTheConnector() {
    FlatSourceSettings rhythm = new FlatSourceSettings(null, null, 7, null);

    assertThat(rhythm.addressedTo(DocumentSourceType.RSS_FEED, true)).isNull();
    assertThatThrownBy(() -> rhythm.rejectForeign(DocumentSourceType.RSS_FEED, true))
        .isInstanceOf(ValidationException.class)
        .hasMessage("confluenceFullSyncIntervalDays ist nur für sourceType CONFLUENCE zulässig");
  }

  @Test
  void theRemainingForeignFieldsAreRefusedEditionS3SpacesRhythm() {
    FlatSourceSettings all =
        new FlatSourceSettings(
            ConfluenceEdition.CLOUD, List.of(FlatSourceSettings.space("A", null)), 7, S3);

    assertThatThrownBy(() -> all.rejectForeign(DocumentSourceType.HTTP_DIRECTORY, false))
        .hasMessage("confluenceEdition ist nur für sourceType CONFLUENCE zulässig");
    assertThatThrownBy(() -> all.rejectForeign(DocumentSourceType.CONFLUENCE, false))
        .hasMessage("s3Settings sind nur für sourceType S3 zulässig");
  }
}
