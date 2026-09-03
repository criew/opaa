package io.opaa.api;

import io.opaa.api.dto.ConfluenceSpaceListRequest;
import io.opaa.api.dto.ConfluenceSpaceListResponse;
import io.opaa.api.dto.ConfluenceSpaceRef;
import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.indexing.source.confluence.ConfluenceSpace;
import io.opaa.library.ConfluenceSpaceListing;
import io.opaa.library.SourceConnectionTest;
import io.opaa.library.SourceConnectionTestResult;
import java.util.List;

/**
 * Maps {@link SourceConnectionTestResult} onto its generated response counterpart, and {@link
 * SourceConnectionTestRequest} onto the domain-level {@link SourceConnectionTest} (ADR-0006: API
 * DTOs are generated from the specification, never hand-written).
 */
final class SourceConnectionTestResponseMapper {

  private SourceConnectionTestResponseMapper() {}

  static SourceConnectionTest toDomain(SourceConnectionTestRequest request) {
    return new SourceConnectionTest(
        request.getSourceType(),
        request.getSourcePath(),
        request.getSourceUrl(),
        request.getSourceProxy(),
        request.getSourceCredentials(),
        request.getSourceInsecureSsl(),
        request.getLibraryId(),
        request.getConfluenceEdition());
  }

  static ConfluenceSpaceListing toDomain(ConfluenceSpaceListRequest request) {
    return new ConfluenceSpaceListing(
        request.getSourceUrl(),
        request.getConfluenceEdition(),
        request.getSourceCredentials(),
        request.getSourceProxy(),
        request.getSourceInsecureSsl(),
        request.getLibraryId());
  }

  static ConfluenceSpaceListResponse toResponse(List<ConfluenceSpaceRef> spaces) {
    return new ConfluenceSpaceListResponse(spaces);
  }

  static List<ConfluenceSpaceRef> toRefs(List<ConfluenceSpace> spaces) {
    return spaces.stream()
        .map(space -> new ConfluenceSpaceRef(space.key()).name(space.name()))
        .toList();
  }

  static SourceConnectionTestResponse toResponse(SourceConnectionTestResult result) {
    return new SourceConnectionTestResponse(result.reachable(), result.message())
        .documentCount(result.documentCount())
        .confluenceEdition(result.confluenceEdition())
        .credentialsVerified(result.credentialsVerified());
  }
}
