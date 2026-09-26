package io.opaa.api;

import io.opaa.api.dto.ConfluenceSpaceListRequest;
import io.opaa.api.dto.ConfluenceSpaceListResponse;
import io.opaa.api.dto.ConfluenceSpaceRef;
import io.opaa.api.dto.S3BucketListRequest;
import io.opaa.api.dto.S3BucketListResponse;
import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceListing;
import io.opaa.library.SourceBrowseRequest;
import io.opaa.library.SourceConnectionTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps {@link SourceConnectionTestResult} onto its generated response counterpart, and {@link
 * SourceConnectionTestRequest} onto the domain-level {@link SourceConnectionTest} (ADR-0006: API
 * DTOs are generated from the specification, never hand-written). The per-type fields of the flat
 * API become the connector settings of the request's type (ADR-0038).
 */
final class SourceConnectionTestResponseMapper {

  private SourceConnectionTestResponseMapper() {}

  /** Malformed {@code s3Settings} are the caller's 400 whatever the type, as before any check. */
  static SourceConnectionTest toDomain(
      SourceConnectionTestRequest request, SourceConnectorRegistry connectors) {
    ConnectorData s3Settings =
        FlatSourceSettings.readS3Settings(request.getS3Settings(), connectors);
    ConnectorData connectorSettings = null;
    if (request.getSourceType() == DocumentSourceType.S3) {
      connectorSettings = s3Settings;
    } else if (request.getSourceType() == DocumentSourceType.CONFLUENCE
        && request.getConfluenceEdition() != null) {
      connectorSettings =
          ConnectorData.of(Map.of("edition", request.getConfluenceEdition().name()));
    }
    return new SourceConnectionTest(
        request.getSourceType(),
        request.getSourcePath(),
        request.getSourceUrl(),
        request.getSourceProxy(),
        request.getSourceCredentials(),
        request.getSourceInsecureSsl(),
        request.getLibraryId(),
        connectorSettings);
  }

  static SourceBrowseRequest toDomain(S3BucketListRequest request) {
    Map<String, Object> query = new LinkedHashMap<>();
    if (request.getRegion() != null) {
      query.put("region", request.getRegion());
    }
    if (request.getPathStyle() != null) {
      query.put("pathStyle", request.getPathStyle());
    }
    return new SourceBrowseRequest(
        DocumentSourceType.S3,
        request.getSourceUrl(),
        request.getSourceCredentials(),
        request.getSourceProxy(),
        request.getSourceInsecureSsl(),
        ConnectorData.of(query),
        request.getLibraryId());
  }

  static S3BucketListResponse toResponse(SourceListing listing) {
    return new S3BucketListResponse(
            listing.complete(), listing.entries().stream().map(SourceListing.Entry::key).toList())
        .message(listing.message());
  }

  static SourceBrowseRequest toDomain(ConfluenceSpaceListRequest request) {
    return new SourceBrowseRequest(
        DocumentSourceType.CONFLUENCE,
        request.getSourceUrl(),
        request.getSourceCredentials(),
        request.getSourceProxy(),
        request.getSourceInsecureSsl(),
        request.getConfluenceEdition() == null
            ? null
            : ConnectorData.of(Map.of("edition", request.getConfluenceEdition().name())),
        request.getLibraryId());
  }

  static ConfluenceSpaceListResponse toResponse(List<ConfluenceSpaceRef> spaces) {
    return new ConfluenceSpaceListResponse(spaces);
  }

  static List<ConfluenceSpaceRef> toRefs(SourceListing spaces) {
    return spaces.entries().stream()
        .map(space -> new ConfluenceSpaceRef(space.key()).name(space.name()))
        .toList();
  }

  static SourceConnectionTestResponse toResponse(SourceConnectionTestResult result) {
    return new SourceConnectionTestResponse(result.reachable(), result.message())
        .documentCount(result.documentCount())
        .confluenceEdition(FlatSourceSettings.detectedEdition(result.details()))
        .credentialsVerified(result.credentialsVerified())
        .s3Scopes(FlatSourceSettings.scopeChecks(result.details()));
  }
}
