package io.opaa.library.web;

import io.opaa.api.dto.SourceBrowseEntry;
import io.opaa.api.dto.SourceBrowseRequest;
import io.opaa.api.dto.SourceBrowseResponse;
import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.api.dto.SourceTypeDescriptor;
import io.opaa.connection.ConnectorReleaseService.TypeCreation;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceListing;
import io.opaa.library.SourceConnectionTest;

/**
 * Maps the connection test, the listing before saving and the source type descriptors between their
 * generated counterparts and the domain (ADR-0006: API DTOs are generated from the specification,
 * never hand-written). Connector settings and findings travel as opaque objects (ADR-0038).
 */
final class SourceConnectionTestResponseMapper {

  private SourceConnectionTestResponseMapper() {}

  static SourceConnectionTest toDomain(SourceConnectionTestRequest request) {
    return new SourceConnectionTest(
        LibraryResponseMapper.toSourceType(request.getSourceType()),
        request.getSourcePath(),
        request.getSourceUrl(),
        request.getSourceProxy(),
        request.getSourceCredentials(),
        request.getSourceInsecureSsl(),
        request.getLibraryId(),
        LibraryResponseMapper.toSettings(request.getSourceSettings()),
        request.getConnectionProfileId());
  }

  static SourceConnectionTestResponse toResponse(SourceConnectionTestResult result) {
    return new SourceConnectionTestResponse(result.reachable(), result.message())
        .documentCount(result.documentCount())
        .credentialsVerified(result.credentialsVerified())
        .details(result.details() == null ? null : result.details().asMap());
  }

  static io.opaa.library.SourceBrowseRequest toDomain(
      String sourceType, SourceBrowseRequest request) {
    return new io.opaa.library.SourceBrowseRequest(
        LibraryResponseMapper.toSourceType(sourceType),
        request.getSourceUrl(),
        request.getSourceCredentials(),
        request.getSourceProxy(),
        request.getSourceInsecureSsl(),
        LibraryResponseMapper.toSettings(request.getQuery()),
        request.getLibraryId(),
        request.getConnectionProfileId());
  }

  static SourceBrowseResponse toResponse(SourceListing listing) {
    return new SourceBrowseResponse(
            listing.complete(),
            listing.entries().stream()
                .map(entry -> new SourceBrowseEntry(entry.key()).name(entry.name()))
                .toList())
        .message(listing.message());
  }

  static SourceTypeDescriptor toResponse(
      SourceConnectorDescriptor descriptor, boolean browsable, TypeCreation creation) {
    return new SourceTypeDescriptor()
        .creatable(creation.creatable())
        .creatableWithOwnAddress(creation.withOwnAddress())
        .locked(creation.locked())
        .creationNotice(creation.notice())
        .type(descriptor.type().key())
        .displayName(descriptor.displayName())
        .indexingRun(descriptor.indexingRun())
        .uploads(descriptor.uploads())
        .pushIntake(descriptor.pushIntake() != null)
        .browsable(browsable)
        .profileSupport(descriptor.profileSupport())
        .authMethods(descriptor.authMethods().stream().sorted().toList())
        .fullSyncIntervalDefaultDays(
            descriptor.fullSyncInterval() == null
                ? null
                : (int) Math.max(1, descriptor.fullSyncInterval().toDays()));
  }
}
