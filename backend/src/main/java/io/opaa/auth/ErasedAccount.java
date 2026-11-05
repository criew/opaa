package io.opaa.auth;

/**
 * The nameless shell an account becomes when its personal reference is removed ({@link
 * User#erase}): an issuer no provider and no local token carries, so nothing signs in as it, and
 * the neutral name every view shows in place of the person.
 */
public final class ErasedAccount {

  public static final String ISSUER = "urn:opaa:erased";

  public static final String DISPLAY_NAME = "Gelöschtes Konto";

  private ErasedAccount() {}
}
