/**
 * Local accounts (ADR-0033): the local issuer (access and refresh tokens, revocation, action
 * tokens), passwords and lockout, self-service, the handover to an identity provider, the bootstrap
 * seed, the local account administration and the account overview, and the security filter chain of
 * the {@code oidc} profile. The account identity stays {@code users(subject, issuer)} in {@code
 * io.opaa.auth}, with {@link io.opaa.auth.LocalIssuer#URN} as the local issuer; so do the
 * credentials row and the rule that decides whether an account may sign in, which token processing
 * needs as well.
 */
package io.opaa.account;
