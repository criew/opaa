package io.opaa.connection.oauth;

import io.opaa.indexing.source.OAuthAuth;

/**
 * The issuer an authorization response must come from (RFC 9207, 2.4): {@code iss} equals {@code
 * expected} character for character, without normalization, and is required where the server
 * announced it. Without a known issuer every response is accepted.
 *
 * @param expected the issuer, {@code null} where none is known
 * @param required whether the response must name it
 */
public record ResponseIssuer(String expected, boolean required) {

  /** What the sign-in {@code auth} declares or discovered. */
  public static ResponseIssuer of(OAuthAuth auth) {
    return new ResponseIssuer(auth.issuer(), auth.issuerParameterSupported());
  }

  /** Whether a response naming {@code iss} ({@code null} for none) may be completed. */
  public boolean accepts(String iss) {
    if (expected == null) {
      return true;
    }
    return iss == null ? !required : expected.equals(iss);
  }
}
