package io.opaa.auth;

import java.util.UUID;

/**
 * A person created an identity provider or changed what decides whose tokens it accepts and what
 * they grant - issuer, client id, signing keys, e-mail and groups claim, role mapping. Published
 * inside that transaction; {@link AuditorRoles} tells every auditor of the installation, since
 * whoever controls these values can sign in as any account of the provider, an auditor's included.
 *
 * @param providerId the provider
 * @param displayName its name at the time of the change
 * @param created whether the provider is new
 */
public record IdentityProviderTrustChangedEvent(
    UUID providerId, String displayName, boolean created) {}
