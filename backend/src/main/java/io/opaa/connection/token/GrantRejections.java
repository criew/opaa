package io.opaa.connection.token;

import io.opaa.connection.token.SecretOwner.PersonOwned;

/**
 * The port through which the store ends a person's connection whose OAuth grant the provider no
 * longer takes ({@code invalid_grant}): expired, logged and told, in the caller's transaction - the
 * one that holds the token row. The account package answers it.
 */
public interface GrantRejections {

  void grantRejected(PersonOwned owner);
}
