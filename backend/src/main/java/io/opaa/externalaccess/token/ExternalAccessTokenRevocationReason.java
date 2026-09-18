package io.opaa.externalaccess.token;

import io.opaa.api.types.ExternalAccessTokenStatus;

/**
 * Why a token stopped working. Mirrored by {@code chk_external_access_tokens_revocation_reason};
 * the public state derived from it is {@link ExternalAccessTokenStatus}. Deliberately not part of
 * the API: the administration's list needs "who ended this", not the internal vocabulary.
 */
public enum ExternalAccessTokenRevocationReason {
  /** The person withdrew their own token. */
  OWNER(ExternalAccessTokenStatus.REVOKED),
  /** The Systemverwaltung blocked it - an incident, or a Sperre je Person. */
  ADMIN(ExternalAccessTokenStatus.BLOCKED),
  /**
   * The access of the person behind it ended - the account was locked, or it was handed over to a
   * provider identity. Set by {@code ExternalAccessTokenAccountLifecycleListener}.
   */
  ACCOUNT_LIFECYCLE(ExternalAccessTokenStatus.BLOCKED),
  /** Its expiry passed - the common case, not the exception. */
  EXPIRED(ExternalAccessTokenStatus.EXPIRED);

  private final ExternalAccessTokenStatus status;

  ExternalAccessTokenRevocationReason(ExternalAccessTokenStatus status) {
    this.status = status;
  }

  public ExternalAccessTokenStatus status() {
    return status;
  }
}
