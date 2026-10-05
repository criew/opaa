package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.indexing.source.SignInDetails;
import java.util.UUID;

/**
 * What a profile's sign-in needs, read for {@code connection.oauth} only: the registration with its
 * decrypted secret (for a service account key the key file), the sign-in its connector declares,
 * the endpoints the profile names, the imitated account and the profile's proxy. The TLS switch is
 * the server address's and has no field here. {@link #toString} never shows the secret.
 *
 * @param resource the resource indicator (RFC 8707) every authorization, token and renewal request
 *     names - an MCP server's address -, {@code null} for a connector
 * @param secret {@code null} when none is stored or it cannot be decrypted
 * @param scopes the profile's scopes, {@code null} for the declared default
 * @param version the profile's row version the values were read at
 */
public record ClientRegistration(
    UUID profileId,
    ConnectionAuthMethod method,
    String clientId,
    String secret,
    String tenant,
    String scopes,
    SignInDetails signIn,
    String subject,
    String proxy,
    boolean signInRejected,
    ProfileEndpoints endpoints,
    long version,
    String resource) {

  /** The registration of a connector profile, which names no resource. */
  public ClientRegistration(
      UUID profileId,
      ConnectionAuthMethod method,
      String clientId,
      String secret,
      String tenant,
      String scopes,
      SignInDetails signIn,
      String subject,
      String proxy,
      boolean signInRejected,
      ProfileEndpoints endpoints,
      long version) {
    this(
        profileId,
        method,
        clientId,
        secret,
        tenant,
        scopes,
        signIn,
        subject,
        proxy,
        signInRejected,
        endpoints,
        version,
        null);
  }

  @Override
  public String toString() {
    return "ClientRegistration[profileId="
        + profileId
        + ", method="
        + method
        + ", clientId="
        + clientId
        + ", secret=***, signInRejected="
        + signInRejected
        + "]";
  }
}
