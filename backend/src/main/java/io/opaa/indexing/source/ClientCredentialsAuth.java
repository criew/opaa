package io.opaa.indexing.source;

import java.util.Objects;

/**
 * The sign-in "Client-Credentials" a connector offers: the profile's client id and secret go to
 * {@code token}, the only target outside the server address they may reach; the connector gets the
 * access token alone. {@code defaultScope} stands where the profile names no scopes ({@code null}
 * for none).
 */
public record ClientCredentialsAuth(
    Endpoint token, String defaultScope, ClientAuthentication clientAuth) implements SignInDetails {

  public ClientCredentialsAuth {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(clientAuth, "clientAuth");
    if (defaultScope != null && defaultScope.isBlank()) {
      defaultScope = null;
    }
  }
}
