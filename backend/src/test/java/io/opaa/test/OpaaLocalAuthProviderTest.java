package io.opaa.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link OpaaLocalAuthMockMvcTest} plus an OIDC provider whose tokens are actually verifiable
 * (#1563, ADR-0033 Entscheidung 12): {@link OidcProviderTokenTestConfiguration} supplies the
 * decoder factory, and the SSRF check of the sign-in is off so a provider row with a non-resolvable
 * test issuer reaches that factory at all.
 *
 * <p><b>Why this cannot be the base signature:</b> the handover is redeemed with a token of a real
 * provider, and the base signature has none - its factory would have to fetch a JWK set over the
 * network. Replacing that factory for every class of the family would take the production decoder
 * out of the contexts that assert on it ({@code OidcProviderRegistry} skipping an unreachable
 * provider, the {@code unknown_issuer} refusal), and switching the address policy off globally
 * would take the SSRF rule of ADR-0025 with it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OpaaLocalAuthMockMvcTest
@TestPropertySource(properties = {"opaa.auth.oidc.target-validation.enabled=false"})
@Import(OidcProviderTokenTestConfiguration.class)
public @interface OpaaLocalAuthProviderTest {}
