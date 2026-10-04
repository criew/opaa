package io.opaa.auth;

/**
 * What switching a sign-in provider off or deleting it does to persons' connections (ADR-0041,
 * Entscheidung 4): switched off, their connections rest; deleted, they end and the deletion period
 * of their private libraries begins. The provider administration asks before it acts; the module
 * that keeps the connections answers only whether persons may have connections at all - never a
 * number per provider, which could be offset against the numbers per profile.
 */
public interface ProviderConnectionsImpact {

  /** Whether any connection profile admits persons; independent of any provider and account. */
  boolean personsAdmitted();
}
