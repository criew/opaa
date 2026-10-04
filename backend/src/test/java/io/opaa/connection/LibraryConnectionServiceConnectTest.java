package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectionSecrets;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.SecretOwner;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.indexing.source.upload.UploadSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.UploadedOriginalStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Connecting a library to another profile keeps its secret only while origin and the connector's
 * binding both stay, as a draft and a save decide it.
 */
class LibraryConnectionServiceConnectTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");

  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final KnowledgeLibraryRepository libraries = mock(KnowledgeLibraryRepository.class);
  private final ConnectionSecrets secrets = mock(ConnectionSecrets.class);

  @Test
  void aMoveWithinTheOriginDiscardsTheSecretWhenTheConnectorBindsItNarrower() {
    KnowledgeLibrary library = connectedLibrary();

    service(new ShareBoundProbe()).connect(library, profile("https://probe.example.org/b").getId());

    assertThat(library.getSourceUrl()).isEqualTo("https://probe.example.org/b/x");
    verify(secrets).discard(SecretOwner.of(null, library));
  }

  @Test
  void aMoveWithinTheOriginKeepsTheSecretWhereOnlyTheOriginCounts() {
    KnowledgeLibrary library = connectedLibrary();

    service(new ProfileProbeSourceConnector())
        .connect(library, profile("https://probe.example.org/b").getId());

    assertThat(library.getSourceUrl()).isEqualTo("https://probe.example.org/b/x");
    verify(secrets, never()).discard(any());
  }

  /** A library at https://probe.example.org/a/x on a profile for https://probe.example.org/a. */
  private KnowledgeLibrary connectedLibrary() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Ablage",
            null,
            UUID.randomUUID(),
            ProfileProbeSourceConnector.TYPE,
            null,
            "https://probe.example.org/a/x",
            null,
            "nutzer:geheim",
            false);
    ConnectionProfile previous = profile("https://probe.example.org/a");
    when(connections.findById(library.getId()))
        .thenReturn(Optional.of(new LibraryConnection(library.getId(), previous.getId(), NOW)));
    return library;
  }

  private ConnectionProfile profile(String serverUrl) {
    ConnectionProfile profile = new ConnectionProfile(ProfileProbeSourceConnector.TYPE, NOW);
    ReflectionTestUtils.setField(profile, "name", "Zugang " + serverUrl);
    ReflectionTestUtils.setField(profile, "serverUrl", serverUrl);
    ReflectionTestUtils.setField(profile, "authMethod", ConnectionAuthMethod.PERSONAL_SECRET);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.LIBRARY);
    when(profiles.findById(profile.getId())).thenReturn(Optional.of(profile));
    return profile;
  }

  private LibraryConnectionService service(ProfileProbeSourceConnector connector) {
    return new LibraryConnectionService(
        connections,
        profiles,
        libraries,
        new SourceConnectorRegistry(
            List.of(connector, new UploadSourceConnector(mock(UploadedOriginalStore.class)))),
        mock(ConnectorLockService.class),
        secrets,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /** The probe, binding stored credentials to the first path segment like a file share. */
  private static final class ShareBoundProbe extends ProfileProbeSourceConnector {

    @Override
    public boolean keepsCredentials(String storedSourceUrl, String requestedSourceUrl) {
      return firstSegment(storedSourceUrl).equals(firstSegment(requestedSourceUrl));
    }

    private static String firstSegment(String url) {
      return java.net.URI.create(url).getPath().split("/")[1];
    }
  }
}
