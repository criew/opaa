package io.opaa.library;

import io.opaa.api.FlatSourceSettings;
import io.opaa.api.types.ConfluenceEdition;
import io.opaa.indexing.source.confluence.ConfluenceSpaceSelection;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.indexing.source.s3.S3SourceSettingsJson;
import java.util.List;

/**
 * The flat request fields of the library builders as the API hands them to the service - the same
 * {@link FlatSourceSettings} the controller builds, with the S3 settings already read.
 */
final class FlatSourceSettingsFixture {

  private FlatSourceSettingsFixture() {}

  static ConnectorSettingsRequest of(
      ConfluenceEdition edition,
      List<ConfluenceSpaceSelection> spaces,
      Integer fullSyncIntervalDays,
      S3SourceSettings s3Settings) {
    return new FlatSourceSettings(
        edition,
        spaces == null
            ? null
            : spaces.stream()
                .map(space -> FlatSourceSettings.space(space.getSpaceKey(), space.getSpaceName()))
                .toList(),
        fullSyncIntervalDays,
        s3Settings == null ? null : S3SourceSettingsJson.toData(s3Settings));
  }
}
