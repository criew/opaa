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
   * A number about persons as the administration may see it: {@code exact} or {@code fewerThan}.
   */
  record MaskedCount(Long exact, Integer fewerThan) {}

  record Impact(MaskedCount connections, MaskedCount privateLibraries) {}
}
