package io.opaa.api;

import io.opaa.api.dto.ConfluenceSpaceListRequest;
import io.opaa.api.dto.ConfluenceSpaceListResponse;
import io.opaa.api.dto.ConfluenceSpaceRef;
import io.opaa.api.dto.S3BucketListRequest;
import io.opaa.api.dto.S3BucketListResponse;
import io.opaa.api.dto.S3ScopeCheck;
import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.indexing.source.confluence.ConfluenceSpace;
import io.opaa.library.ConfluenceSpaceListing;
import io.opaa.library.S3BucketListResult;
import io.opaa.library.S3BucketListingRequest;
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
        request.getConfluenceEdition(),
        LibraryResponseMapper.toS3Settings(request.getS3Settings()));
  }

  static S3BucketListingRequest toDomain(S3BucketListRequest request) {
    return new S3BucketListingRequest(
        request.getSourceUrl(),
        request.getSourceCredentials(),
        request.getSourceProxy(),
        request.getSourceInsecureSsl(),
        request.getRegion(),
        request.getPathStyle(),
        request.getLibraryId());
  }

  static S3BucketListResponse toResponse(S3BucketListResult result) {
    return new S3BucketListResponse(result.permitted(), result.buckets()).message(result.message());
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
        .credentialsVerified(result.credentialsVerified())
        .s3Scopes(result.s3Scopes() == null ? null : toScopeChecks(result.s3Scopes()));
  }

  private static List<S3ScopeCheck> toScopeChecks(List<io.opaa.library.S3ScopeCheck> checks) {
    return checks.stream()
        .map(
            check ->
                new S3ScopeCheck(
                        check.bucket(),
                        check.prefix(),
                        check.bucketReachable(),
                        check.listAllowed(),
                        check.objectCount(),
                        check.objectCountIsLowerBound())
                    .readAllowed(check.readAllowed())
                    .message(check.message()))
        .toList();
  }
}
