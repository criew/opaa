package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ProfileEndpoints;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.Revocation;
import io.opaa.indexing.source.SignInRejectedException;
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A renewal of a grant issued for another resource than the profile names is refused before the
 * token endpoint is asked: renewing it would carry a token of one MCP server to another.
 */
class ProviderTokensResourceTest {

  private static final String RESOURCE = "https://mcp-a.example.org/mcp";

  private final ProfileRegistrations registrations = mock(ProfileRegistrations.class);
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private final HttpServer tokenEndpoint = startTokenEndpoint();
  private final ProviderTokens tokens =
      new ProviderTokens(
          registrations,
          mock(ProfileSignIn.class),
          TargetAddressValidator.disabled(),
          Clock.systemUTC());

  @AfterEach
  void stop() {
    tokenEndpoint.stop(0);
  }

  @Test
  void aGrantIssuedForAnotherServerIsNotRenewed() {
    UUID profile = UUID.randomUUID();
    when(registrations.registrationOf(profile)).thenReturn(registration(profile));

    assertThatThrownBy(
            () -> tokens.renew(profile, "refresh-token", "https://mcp-b.example.org/mcp"))
        .isExactlyInstanceOf(SignInRejectedException.class);
    assertThat(tokenRequests).hasValue(0);
  }

  @Test
  void aGrantIssuedForTheServerIsRenewedThere() {
    UUID profile = UUID.randomUUID();
    when(registrations.registrationOf(profile)).thenReturn(registration(profile));

    tokens.renew(profile, "refresh-token", RESOURCE);

    assertThat(tokenRequests).hasValue(1);
  }

  private ClientRegistration registration(UUID profile) {
    String token = "http://127.0.0.1:" + tokenEndpoint.getAddress().getPort() + "/token";
    return new ClientRegistration(
        profile,
        ConnectionAuthMethod.OAUTH,
        "opaa",
        null,
        null,
        null,
        new OAuthAuth(
            new Endpoint.FromProfile(),
            new Endpoint.FromProfile(),
            new Revocation.None(),
            null,
            Map.of(),
            ClientAuthentication.CLIENT_SECRET_BASIC),
        null,
        null,
        false,
        new ProfileEndpoints("https://auth.example.org/authorize", token, null),
        0,
        RESOURCE);
  }

  private HttpServer startTokenEndpoint() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/token",
          exchange -> {
            tokenRequests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body =
                "{\"access_token\": \"a\", \"expires_in\": 3600}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
