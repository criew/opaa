package io.opaa.auth;

/**
 * The issuer of local accounts (ADR-0033, Entscheidung 2): {@code users.issuer} of every local
 * account, the {@code iss} claim of every locally minted token and the issuer of the one {@code
 * LOCAL} provider row. A URN, not a URL, so the identity of an account survives a move of the
 * installation to another address.
 */
public final class LocalIssuer {

  public static final String URN = "urn:opaa:local";

  private LocalIssuer() {}
}
