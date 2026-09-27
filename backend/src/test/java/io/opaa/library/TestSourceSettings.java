package io.opaa.library;

import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.confluence.ConfluenceEdition;
import io.opaa.indexing.source.confluence.ConfluenceSourceSettings;
import io.opaa.indexing.source.confluence.ConfluenceSpaceSelection;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.indexing.source.s3.S3SourceSettingsJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code sourceSettings} object of a library request as the builders' per-connector fields
 * describe it - the Confluence parts that are set, or the S3 settings; {@code null} for none.
 */
final class TestSourceSettings {

  private TestSourceSettings() {}

  static ConnectorData of(
      ConfluenceEdition edition,
      List<ConfluenceSpaceSelection> spaces,
      Integer fullSyncIntervalDays,
      S3SourceSettings s3Settings) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (edition != null || spaces != null || fullSyncIntervalDays != null) {
      data.putAll(
          new ConfluenceSourceSettings(edition, spaces, fullSyncIntervalDays).toData().asMap());
    }
    if (s3Settings != null) {
      data.putAll(S3SourceSettingsJson.toData(s3Settings).asMap());
    }
    return data.isEmpty() ? null : ConnectorData.of(data);
  }
}
