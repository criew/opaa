/**
 * The personal access tokens of the external-access channel (ADR-0035, Entscheidung 2;
 * docs/features/external-access.md, "Zugangstokens"). A token is an opaque random value with a
 * fixed prefix, stored only as an HMAC lookup hash under the fourth purpose of {@code
 * io.opaa.security.LocalAuthKeyService}; it is bound to a person, to an immutable selection of
 * knowledge libraries and to a mandatory expiry.
 *
 * <p>The effective view of a token is computed per call and never frozen into it: rights of the
 * person, release of the library, selection of the token and the installation switch - see {@link
 * io.opaa.externalaccess.token.ExternalAccessTokenScopeService}. The switch and the channel's
 * limits come from {@link io.opaa.externalaccess.ExternalAccessSettingsService} (#1717); the
 * release is still a seam, {@link io.opaa.externalaccess.token.ExternalAccessLibraryRelease}
 * (#1731).
 *
 * <p>Nothing here writes a token prefix to a log: over months it is stable and attributable to one
 * person, and together with a timestamp it would be the per-person query history the audit trail
 * deliberately excludes. {@code ExternalAccessTokenPrefixNotLoggedTest} holds that line.
 */
package io.opaa.externalaccess.token;
