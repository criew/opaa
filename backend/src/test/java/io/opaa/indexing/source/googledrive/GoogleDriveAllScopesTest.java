package io.opaa.indexing.source.googledrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The scope "Alle Ablagen": every shared drive the account sees, with an imitated account also its
 * "Meine Ablage"; never together with a selection.
 */
class GoogleDriveAllScopesTest {

  private FakeDriveServer server;
  private GoogleDriveSourceConnector connector;

  @BeforeEach
  void setUp() {
    server = new FakeDriveServer();
    server.addDrive("drive0", "Ablage");
    server.addDrive("drive1", "Archiv");
    connector =
        new GoogleDriveSourceConnector(
            server.base(),
            URI.create("https://oauth2.example.org/token"),
            new DriveApiFactory(
                GoogleDriveProperties.defaults(), TargetAddressValidator.disabled(), wait -> {}),
            mock(SourceSyncStateRepository.class));
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  // regression guard for #3117: an empty scope list was the only way to say "everything" and
  // refused
  @Test
  void allScopesIsAcceptedWithoutASelection() {
    ConnectorData read = connector.readSettings(ConnectorData.of(Map.of("allScopes", true)));

    assertThat(read.asMap()).containsEntry("allScopes", true).containsEntry("scopes", List.of());
  }

  @Test
  void allScopesAndASelectionAreRefusedTogether() {
    assertThatThrownBy(
            () ->
                connector.readSettings(
                    ConnectorData.of(
                        Map.of("allScopes", true, "scopes", List.of(Map.of("drive", "d1"))))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("allScopes");
  }

  @Test
  void theConnectionTestCountsTheSharedDrivesAndTheImitatedAccountsOwnDrive() {
    SourceSettings withoutSubject =
        new SourceSettings(
            null,
            server.base().toString(),
            FakeDriveServer.TOKEN,
            ConnectorData.of(Map.of("allScopes", true)));
    SourceSettings withSubject =
        new SourceSettings(
            null,
            server.base().toString(),
            FakeDriveServer.TOKEN,
            ConnectorData.of(Map.of("allScopes", true, "subject", "fach@example.org")));

    SourceConnectionTestResult shared = connector.testConnection(withoutSubject, null);
    SourceConnectionTestResult own = connector.testConnection(withSubject, null);

    assertThat(shared.reachable()).isTrue();
    assertThat(shared.message()).contains("2 Ablagen");
    assertThat(own.message()).contains("3 Ablagen");
  }
}
