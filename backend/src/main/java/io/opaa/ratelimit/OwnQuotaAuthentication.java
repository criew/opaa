package io.opaa.ratelimit;

/**
 * An authentication by a credential with a quota of its own - the access token of the
 * external-access channel (ADR-0035). {@link RateLimitFilter} gives back its per-address token and
 * counts its budget per account twice: under {@link #quotaKey()} and, shared by all credentials of
 * one person, under {@link #ownerKey()} - more tokens buy no more budget.
 */
public interface OwnQuotaAuthentication {

  /** Identifies the credential, stable for its lifetime; never its secret value. */
  String quotaKey();

  /** Identifies the person the credential belongs to. */
  String ownerKey();
}
