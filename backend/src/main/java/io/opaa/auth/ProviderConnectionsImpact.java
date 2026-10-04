package io.opaa.auth;

import java.util.UUID;

/**
 * What switching a sign-in provider off or deleting it does to persons' connections (ADR-0041,
 * Entscheidung 4): switched off, their connections rest; deleted, they end and the deletion period
 * of their private libraries begins. The provider administration asks before it acts; the module
 * that keeps the connections answers, with masked numbers only.
 */
public interface ProviderConnectionsImpact {

  /** The connections and private libraries of the provider's accounts that are not deactivated. */
  Impact of(UUID providerId);

  /**
   * A number about persons as the administration may see it: exactly one of {@code exact}, {@code
   * fewerThan} and {@code atLeast} is set.
   */
  record MaskedCount(Long exact, Integer fewerThan, Integer atLeast) {}

  /**
   * {@code confirmationRequired} holds wherever persons may have connections at all, so it tells
   * nothing about one; each number is {@code null} where it may not be told.
   */
  record Impact(
      boolean confirmationRequired, MaskedCount connections, MaskedCount privateLibraries) {

    /** No connection of a person can exist: nothing to confirm, nothing to tell. */
    public static Impact none() {
      return new Impact(false, null, null);
    }
  }
}
