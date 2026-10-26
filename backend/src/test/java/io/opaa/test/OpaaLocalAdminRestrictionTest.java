package io.opaa.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link OpaaLocalAuthMockMvcTest} plus a network restriction for local system administrators
 * (ADR-0033, Entscheidung 9).
 *
 * <p><b>Why this cannot be the base signature:</b> {@code opaa.auth.local.admin-allowed-cidrs}
 * would lock the local administrators of every other class of the family out.
 *
 * <p>The external-access feature rides along switched off (ADR-0044): what an access-token value
 * meets under {@code oidc} without the channel's filter chain can only be seen in a context started
 * without it ({@code ExternalAccessSwitchedOffOidcTest}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OpaaLocalAuthMockMvcTest
@TestPropertySource(
    properties = {
      "opaa.auth.local.admin-allowed-cidrs=127.0.0.1/32,10.0.0.0/8",
      "opaa.features.external-access.enabled=false"
    })
public @interface OpaaLocalAdminRestrictionTest {}
