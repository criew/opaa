package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.FakeTokenEndpoint;
import io.opaa.indexing.source.ServiceAccountKey;
import io.opaa.indexing.source.ServiceAccountKeyFixture;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.probe.ProbeKeySourceConnector;
import io.opaa.indexing.source.upload.UploadSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.knowledge.UploadedOriginalStore;
import io.opaa.permission.CapabilityService;
import io.opaa.security.TargetAddressValidator;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Connection test and listing of a connector that signs in with a service account key (ADR-0040):
 * the core exchanges the key, so the connector receives the access token only; a refused key is the
 * test's finding; and a stored key never serves another imitated account than its own.
 */
class SourceConnectionTestServiceKeyTest {

  private final ServiceAccountKeyFixture key = new ServiceAccountKeyFixture();
  private FakeTokenEndpoint endpoint;
  private ProbeKeySourceConnector connector;
  private KnowledgeLibraryRepository libraryRepository;
  private SourceConnectionTestService service;
  private CurrentUser caller;

  @BeforeEach
  void setUp() {
    endpoint = new FakeTokenEndpoint();
    connector = new ProbeKeySourceConnector(endpoint.uri());
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    caller = CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "Caller");
    service =
        new SourceConnectionTestService(
            libraryRepository,
            mock(LibraryAccessService.class),
            new SourceConnectorRegistry(
                List.of(connector, new UploadSourceConnector(mock(UploadedOriginalStore.class)))),
            mock(CapabilityService.class),
            new ServiceAccountTokens(TargetAddressValidator.disabled(), Clock.systemUTC()));
  }

  @AfterEach
  void tearDown() {
    endpoint.close();
  }

  @Test
  void theConnectorIsHandedTheAccessTokenNeverTheKey() {
    endpoint.answer(FakeTokenEndpoint.token("ya29.zugriff", 3600));

    SourceConnectionTestResult result = service.test(request(key.json(), null, null), caller);

    assertThat(result.reachable()).isTrue();
    assertThat(connector.lastSecret()).isEqualTo("ya29.zugriff");
    assertThat(endpoint.forms()).hasSize(1);
  }

  @Test
  void aRefusedKeyIsTheFindingOfTheTest() {
    endpoint.answer(FakeTokenEndpoint.error(400, "invalid_grant", "Invalid JWT Signature."));

    SourceConnectionTestResult result = service.test(request(key.json(), null, null), caller);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).contains("wird nicht angenommen");
    assertThat(connector.lastSecret()).isNull();
  }

  @Test
  void theStoredKeyServesTheStoredSubject() {
    UUID libraryId = storedLibrary("fach-a@example.org");

    service.test(request(null, libraryId, Map.of("subject", "fach-a@example.org")), caller);

    assertThat(endpoint.forms()).hasSize(1);
    assertThat(connector.lastSecret()).isEqualTo("ya29.test-token");
  }

  @Test
  void theStoredKeyNeverServesAnotherSubject() {
    UUID libraryId = storedLibrary("fach-a@example.org");

    service.test(request(null, libraryId, Map.of("subject", "chef@example.org")), caller);
    SourceListing listing =
        service.browse(
            new SourceBrowseRequest(
                ProbeKeySourceConnector.TYPE,
                null,
                null,
                null,
                null,
                ConnectorData.of(Map.of("subject", "chef@example.org")),
                libraryId),
            caller);

    assertThat(endpoint.forms()).as("no assertion is signed for chef@").isEmpty();
    assertThat(connector.lastSecret()).isNull();
    assertThat(listing).isNotNull();
  }

  private SourceConnectionTest request(
      String credentials, UUID libraryId, Map<String, String> settings) {
    return new SourceConnectionTest(
        ProbeKeySourceConnector.TYPE,
        null,
        null,
        null,
        credentials,
        null,
        libraryId,
        settings == null ? null : ConnectorData.of(settings));
  }

  private UUID storedLibrary(String subject) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            caller.organizationId(),
            "Dienstkonto",
            null,
            caller.id(),
            ProbeKeySourceConnector.TYPE,
            null,
            ProbeKeySourceConnector.ADDRESS,
            null,
            ServiceAccountKey.parse(key.json()).storedForm(),
            false);
    library.updateSourceSettings(ConnectorData.of(Map.of("subject", subject)).toJson());
    UUID id = UUID.randomUUID();
    ReflectionTestUtils.setField(library, "id", id);
    when(libraryRepository.findById(any())).thenReturn(Optional.of(library));
    return id;
  }
}
