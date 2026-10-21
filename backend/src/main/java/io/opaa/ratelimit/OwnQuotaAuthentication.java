package io.opaa.ratelimit;

/**
 * An authentication by a credential that carries a quota of its own - the access token of the
 * external-access channel (ADR-0035). {@link RateLimitFilter} gives back the per-address token of
 * such a request, because a budget per address would lock out everyone behind the same address once
 * one script spent it, and keys the budget per account by {@link #quotaKey()}, apart from the
 * account the credential belongs to.
 */
public interface OwnQuotaAuthentication {

  /** Identifies the credential, stable for its lifetime; never its secret value. */
  String quotaKey();
}
