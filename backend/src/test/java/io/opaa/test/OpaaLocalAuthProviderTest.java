package io.opaa.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * {@link OpaaLocalAuthMockMvcTest} plus an OIDC provider whose tokens are actually verifiable
 * (#1563, ADR-0033 Entscheidung 12): {@link OidcProviderTokenTestConfiguration} supplies the
 * decoder factory the provider registry builds its decoders with.
 *
 * <p><b>Why this cannot be the base signature:</b> a handover is redeemed with a token of a real
 * provider, and the production factory would have to fetch that provider's JWK set over the
 * network. Replacing the factory for the whole family would put a test double in every context of
 * it - including the classes that drive the local issuer's own decoder right next to it - to serve
 * the one class that needs a provider token. The replacement is therefore imported here and nowhere
 * else. The address check of the sign-in stays on in both signatures; what keeps it off the network
 * is the allowlisted fixture host of the base signature.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OpaaLocalAuthMockMvcTest
@Import(OidcProviderTokenTestConfiguration.class)
public @interface OpaaLocalAuthProviderTest {}
