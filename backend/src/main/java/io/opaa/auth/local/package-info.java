/**
 * Local accounts (Epic #1529, ADR-0033): the schema-side foundation - the 1:1 credentials row of a
 * local {@code users} entry, the three token tables of the local issuer, the singleton settings,
 * the {@code opaa.auth.local.*} configuration and the startup guard that refuses to run the {@code
 * oidc} profile without a strong {@code OPAA_AUTH_JWT_SECRET}. The issuer itself (tokens, refresh,
 * revocation), the bootstrap seed and the account lifecycle build on this package; the account
 * identity stays {@code users(subject, issuer)} in {@code io.opaa.auth}, with {@link
 * io.opaa.auth.LocalIssuer#URN} as the local issuer.
 */
package io.opaa.auth.local;
