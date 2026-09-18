package io.opaa.api.types;

/**
 * The state of a personal access token (ADR-0035, Entscheidung 2), derived from the row rather than
 * stored: {@code ACTIVE} until its expiry passes ({@code EXPIRED}) or it is withdrawn - by the
 * person themselves ({@code REVOKED}) or by the Systemverwaltung ({@code BLOCKED}). The two
 * withdrawals stay distinguishable because the administration's list is a Bestandsliste for
 * Sperrentscheidungen: "who ended this" is the one fact it needs about a dead token.
 */
public enum ExternalAccessTokenStatus {
  ACTIVE,
  EXPIRED,
  REVOKED,
  BLOCKED
}
