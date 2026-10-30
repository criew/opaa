package io.opaa.auth;

/**
 * {@code user} leaves the AUDITOR role - by a role change, a provider's claim or the deletion of
 * the account. Published inside the transaction that writes it; {@link AuditorRoles} tells every
 * remaining auditor.
 */
public record AuditorRoleRevokedEvent(User user) {}
