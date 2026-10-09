package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.FakeMcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Discovery of an MCP server's authorization server (RFC 9728, RFC 8414) trusts no answer: the
 * resource must be the server, the issuer the address its metadata came from, PKCE must offer S256,
 * the address must be https or loopback.
 */
class McpServerDiscoveryTest {

  private final FakeMcpServer server = new FakeMcpServer();
  private final McpServerDiscovery discovery =
      new McpServerDiscovery(TargetAddressValidator.disabled());

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void findsTheAuthorizationServerThroughTheResourceMetadataWithThePathInserted() {
    McpServerDiscovery.Metadata metadata = discovery.discover(server.resource());

    assertThat(metadata.issuer()).isEqualTo(server.issuer());
    assertThat(metadata.authorizationEndpoint()).isEqualTo(server.authorizationEndpoint());
    assertThat(metadata.tokenEndpoint()).isEqualTo(server.tokenEndpoint());
    assertThat(metadata.revocationEndpoint()).isEqualTo(server.revocationEndpoint());
    assertThat(metadata.scopes()).isEqualTo("tools.read offline_access");
    assertThat(server.metadataRequests())
        .containsExactly(
            "/.well-known/oauth-protected-resource/mcp",
            "/.well-known/oauth-authorization-server/auth");
  }

  @Test
  void readsWhetherTheAuthorizationResponseNamesItsIssuer() {
    assertThat(discovery.discover(server.resource()).issuerParameterSupported()).isFalse();

    server.announceIssuerParameter(false);
    assertThat(discovery.discover(server.resource()).issuerParameterSupported()).isFalse();

    server.announceIssuerParameter(true);
    assertThat(discovery.discover(server.resource()).issuerParameterSupported()).isTrue();
  }

  @Test
  void refusesMetadataNamingAnotherResource() {
    server.announceResource("http://127.0.0.1:1/mcp");

    assertThatThrownBy(() -> discovery.discover(server.resource()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("andere Ressource");
  }

  @Test
  void refusesAnAuthorizationServerAnnouncingAnotherIssuer() {
    server.announceIssuer("https://angreifer.example.org");

    assertThatThrownBy(() -> discovery.discover(server.resource()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("meldet sich als https://angreifer.example.org");
  }

  @Test
  void refusesAnAuthorizationServerWithoutPkceS256() {
    server.offerS256(false);

    assertThatThrownBy(() -> discovery.discover(server.resource()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("S256");
  }

  @Test
  void refusesAServerWithoutMetadata() {
    assertThatThrownBy(() -> discovery.discover(server.resource() + "/anders"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nicht gefunden");
  }

  @Test
  void refusesAnAddressThatTheTargetCheckBlocks() {
    McpServerDiscovery guarded =
        new McpServerDiscovery(new TargetAddressValidator(true, java.util.List.of()));

    assertThatThrownBy(() -> guarded.discover(server.resource()))
        .isInstanceOf(ValidationException.class);
    assertThat(server.metadataRequests()).isEmpty();
  }
}
