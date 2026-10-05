package io.opaa.connection.token;

import io.opaa.connection.token.SecretOwner.SourceConsent;

/**
 * The port through which the store ends a library's own consent whose OAuth grant the provider no
 * longer takes ({@code invalid_grant}): ended, logged and told to those responsible, in the
 * caller's transaction - the one that holds the token row. Answered above this package.
 */
public interface SourceConsentRejections {

  void consentRejected(SourceConsent owner);
}
