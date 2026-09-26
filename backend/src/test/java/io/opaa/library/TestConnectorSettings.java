package io.opaa.library;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.indexing.source.s3.S3SourceSettingsJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The connector settings of a probe or a listing as the controller builds them from the flat
 * request fields, and the per-type findings of a probe - for service tests below the API.
 */
final class TestConnectorSettings {

  private TestConnectorSettings() {}

  /** The Confluence edition, else the S3 settings of a probe; {@code null} for neither. */
  static ConnectorData of(ConfluenceEdition edition, S3SourceSettings s3Settings) {
    if (edition != null) {
      return ConnectorData.of(Map.of("edition", edition.name()));
    }
    return s3Settings == null ? null : S3SourceSettingsJson.toData(s3Settings);
  }

  /** The query of an S3 bucket listing. */
  static ConnectorData s3Query(String region, Boolean pathStyle) {
    Map<String, Object> query = new LinkedHashMap<>();
    if (region != null) {
      query.put("region", region);
    }
    if (pathStyle != null) {
      query.put("pathStyle", pathStyle);
    }
    return ConnectorData.of(query);
  }

  static ConfluenceEdition edition(SourceConnectionTestResult result) {
    return result.details() == null || result.details().get("edition") == null
        ? null
        : ConfluenceEdition.valueOf(result.details().get("edition").toString());
  }

  /** The scope findings of an S3 probe in their JSON form. */
  static List<Object> scopes(SourceConnectionTestResult result) {
    return result.details() == null ? null : List.copyOf((List<?>) result.details().get("scopes"));
  }
}
