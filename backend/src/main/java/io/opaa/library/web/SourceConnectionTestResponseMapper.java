package io.opaa.library.web;

import io.opaa.api.dto.ProfileDefaultKey;
import io.opaa.api.dto.SignInProfileEndpoints;
import io.opaa.api.dto.SourceBrowseEntry;
import io.opaa.api.dto.SourceBrowseRequest;
import io.opaa.api.dto.SourceBrowseResponse;
import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.api.dto.SourceTypeDescriptor;
import io.opaa.api.dto.SourceTypeSignIn;
import io.opaa.connection.ConnectorReleaseService.TypeCreation;
import io.opaa.indexing.spi.ClientCredentialsAuth;
import io.opaa.indexing.spi.DefaultKey;
import io.opaa.indexing.spi.Endpoint;
import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.OAuthAuth;
import io.opaa.indexing.spi.ProfileDeclaration;
import io.opaa.indexing.spi.SignIn;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import io.opaa.indexing.spi.SourceListing;
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
        request.getSourceCredentials(),
        request.getLibraryId(),
        LibraryResponseMapper.toSettings(request.getSourceSettings()),
        request.getConnectionProfileId(),
        Boolean.TRUE.equals(request.getPrivateLibrary()),
        request.getPendingConnectionId());
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
        LibraryResponseMapper.toSettings(request.getQuery()),
        request.getLibraryId(),
        request.getConnectionProfileId(),
        Boolean.TRUE.equals(request.getPrivateLibrary()),
        request.getPendingConnectionId());
  }

  static SourceBrowseResponse toResponse(SourceListing listing) {
    return new SourceBrowseResponse(
            listing.complete(),
            listing.entries().stream()
                .map(entry -> new SourceBrowseEntry(entry.key()).name(entry.name()))
                .toList())
        .message(listing.message());
  }

  /** The descriptor with the factory default of its full-sync rhythm. */
  static SourceTypeDescriptor toResponse(
      SourceConnectorDescriptor descriptor, boolean browsable, TypeCreation creation) {
    return toResponse(
        descriptor, browsable, creation, FullSyncRhythm.wholeDays(descriptor.fullSyncInterval()));
  }

  /** The descriptor with the installation-wide full-sync rhythm in whole days, if it has one. */
  static SourceTypeDescriptor toResponse(
      SourceConnectorDescriptor descriptor,
      boolean browsable,
      TypeCreation creation,
      Integer fullSyncIntervalDefaultDays) {
    ProfileDeclaration declaration = descriptor.profileDeclaration();
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
        .profileSupport(declaration.support())
        .profileRequired(creation.profileRequired())
        .signIns(
            declaration.signIns().stream()
                .map(SourceConnectionTestResponseMapper::toResponse)
                .toList())
        .profileDefaults(
            declaration.defaults().keys().stream()
                .map(SourceConnectionTestResponseMapper::toResponse)
                .toList())
        .serverAddress(
            new io.opaa.api.dto.ServerAddressRule(declaration.address().schemes())
                .fixed(declaration.address().fixed()))
        .fullSyncIntervalDefaultDays(fullSyncIntervalDefaultDays);
  }

  private static SourceTypeSignIn toResponse(SignIn signIn) {
    SourceTypeSignIn response =
        new SourceTypeSignIn(signIn.method(), signIn.owners().stream().sorted().toList())
            .secretForm(signIn.secretForm());
    if (signIn.details() instanceof OAuthAuth auth) {
      response.defaultScopes(auth.defaultScopes());
      if (auth.endpointsFromProfile()) {
        response.profileEndpoints(
            new SignInProfileEndpoints(
                fromProfile(auth.authorization()),
                fromProfile(auth.token()),
                fromProfile(auth.revocation().endpoint())));
      }
    } else if (signIn.details() instanceof ClientCredentialsAuth auth) {
      response.defaultScopes(auth.defaultScope());
      if (fromProfile(auth.token())) {
        response.profileEndpoints(new SignInProfileEndpoints(false, true, false));
      }
    }
    return response;
  }

  private static boolean fromProfile(Endpoint endpoint) {
    return endpoint instanceof Endpoint.FromProfile;
  }

  private static ProfileDefaultKey toResponse(DefaultKey key) {
    return new ProfileDefaultKey(key.key(), key.label(), key.kind(), key.choices())
        .profileOnly(key.profileOnly());
  }
}
