package io.opaa.externalaccess.token;

/**
 * Why a presented access token was refused (ADR-0035, Entscheidung 2). The marker goes into the
 * {@code error_description} of {@code WWW-Authenticate}, the same way the local issuer names its
 * reasons (ADR-0033, Entscheidung 8): a tool that gets "channel_closed" needs to wait, one that
 * gets "token_revoked" needs a new token, and neither should have to guess.
 *
 * <p>The markers are deliberately coarse on the "unknown" side: an unknown, a malformed and a
 * foreign value are one and the same answer, so the channel never confirms the existence of a
 * token.
 */
public enum ExternalAccessTokenRejection {

  /** No value matched - unknown, malformed, or of a person that no longer exists. */
  INVALID_TOKEN("invalid_token"),

  /** The token existed and was withdrawn - by the person or by the Systemverwaltung. */
  TOKEN_REVOKED("token_revoked"),

  /** The token reached its expiry. */
  TOKEN_EXPIRED("token_expired"),

  /** The installation switch is off - the Notaus, effective on the very next call. */
  CHANNEL_CLOSED("channel_closed"),

  /** The call came from outside the network range the channel is limited to. */
  NETWORK_NOT_ALLOWED("network_not_allowed"),

  /** The account behind the token is locked, expired, deactivated or otherwise not signed-in. */
  ACCOUNT_NOT_ACTIVE("account_not_active");

  private final String marker;

  ExternalAccessTokenRejection(String marker) {
    this.marker = marker;
  }

  /** What {@code WWW-Authenticate} carries. */
  public String marker() {
    return marker;
  }
}
