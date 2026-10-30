package io.opaa.auth;

/**
 * The AUDITOR role took effect for {@code user} - by an auditor's confirmation, as the first
 * auditor of the organization or through a provider's roles claim. Published synchronously inside
 * the transaction that wrote the role, so a listener that refuses rolls the grant back with it.
 */
public record AuditorRoleGrantedEvent(User user) {}
