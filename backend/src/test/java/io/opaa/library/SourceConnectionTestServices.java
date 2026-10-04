package io.opaa.library;

import static org.mockito.Mockito.mock;

import io.opaa.connection.ConnectorReleaseService;
import io.opaa.connection.LibraryConnectionService;
import io.opaa.connection.profile.OwnAddressDrafts;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;

/** {@link SourceConnectionTestService} over an installation without profiles. */
final class SourceConnectionTestServices {

  private SourceConnectionTestServices() {}

  static SourceConnectionTestService over(
      KnowledgeLibraryRepository libraries,
      LibraryAccessService access,
      SourceConnectorRegistry registry,
      ConnectorReleaseService release,
      ServiceAccountTokens tokens) {
    return new SourceConnectionTestService(
        libraries,
        access,
        registry,
        release,
        mock(LibraryConnectionService.class),
        OwnAddressDrafts.over(registry, libraries, tokens),
        mock(io.opaa.connection.PrivateLibraryConnections.class));
  }
}
