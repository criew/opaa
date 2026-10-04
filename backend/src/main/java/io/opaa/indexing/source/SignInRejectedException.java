package io.opaa.indexing.source;

/**
 * The token endpoint refused the registration itself - an unknown, revoked or wrong key or client
 * secret - so asking again with the same registration fails again. A network failure or a refused
 * scope or account is no such refusal. The German message never carries key, secret or assertion.
 */
public class SignInRejectedException extends SourceCredentialsException {

  public SignInRejectedException(String message) {
    super(message);
  }
}
