/**
 * Identity providers a deployment accepts sign-ins from (#1329, Epic #1294, ADR-0025): the {@code
 * oidc_providers} rows, their admin service, the registry the multi-issuer resolver of {@code
 * io.opaa.auth.OidcSecurityConfig} consults per request, the connection test and the one-time
 * takeover of the {@code OPAA_OIDC_*} environment configuration. The account identity itself stays
 * {@code users(subject, issuer)} in {@code io.opaa.auth}.
 */
package io.opaa.auth.oidc;
