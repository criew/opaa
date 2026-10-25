package io.opaa.indexing.source.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.indexing.spi.OAuthAuth;
import io.opaa.indexing.spi.ProfileDeclaration;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.msgraph.FakeGraphServer;
import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * The OneDrive bean as shipped: Microsoft Graph and Entra at their fixed addresses. The Spring
 * contexts of the suite replace it ({@link OneDriveTestSeam}), so only this test sees the
 * configuration's own declaration.
 */
class SharePointConnectorConfigurationTest {

  @Test
  void theShippedOneDriveConnectorReadsGraphAndSignsInAtEntra() {
    try (FakeGraphServer server = new FakeGraphServer()) {
      OneDriveSourceConnector connector =
          new SharePointConnectorConfiguration()
              .oneDriveSourceConnector(
                  SharePointTestStores.connections(server), mock(SourceSyncStateRepository.class));

      ProfileDeclaration declaration = connector.descriptor().profileDeclaration();
      assertThat(declaration.address().fixed()).isEqualTo("https://graph.microsoft.com");
      OAuthAuth auth = (OAuthAuth) declaration.signIns().getFirst().details();
      assertThat(auth.authorization().resolve("contoso.onmicrosoft.com"))
          .isEqualTo(
              URI.create(
                  "https://login.microsoftonline.com/contoso.onmicrosoft.com/oauth2/v2.0/authorize"));
      assertThat(auth.token().resolve("contoso.onmicrosoft.com"))
          .isEqualTo(
              URI.create(
                  "https://login.microsoftonline.com/contoso.onmicrosoft.com/oauth2/v2.0/token"));
    }
  }
}
