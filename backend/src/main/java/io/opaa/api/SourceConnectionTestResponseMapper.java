package io.opaa.api;

import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.library.SourceConnectionTest;
import io.opaa.library.SourceConnectionTestResult;

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
        request.getLibraryId());
  }

  static SourceConnectionTestResponse toResponse(SourceConnectionTestResult result) {
    return new SourceConnectionTestResponse(result.reachable(), result.message())
        .documentCount(result.documentCount());
  }
}
