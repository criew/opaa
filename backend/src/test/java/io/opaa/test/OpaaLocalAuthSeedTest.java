package io.opaa.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link OpaaLocalAuthMockMvcTest} plus a deliverable initial administrator address and a network
 * restriction for local system administrators (ADR-0033, Entscheidungen 5 and 9).
 *
 * <p><b>Why this cannot be the base signature:</b> the seeding class needs the network restriction
 * {@code opaa.auth.local.admin-allowed-cidrs}, which would lock the local administrators of every
 * other class of the family out, and an address of its own that no other class seeds.
 *
 * <p>The external-access feature rides along switched off for the same reason (ADR-0044): what an
 * access-token value meets under {@code oidc} without the channel's filter chain can only be seen
 * in a context started without it, and the seeding never touches the channel ({@code
 * ExternalAccessSwitchedOffOidcTest}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OpaaLocalAuthMockMvcTest
@TestPropertySource(
    properties = {
      "opaa.auth.initial-admin-email=" + OpaaLocalAuthSeedTest.INITIAL_ADMIN_EMAIL,
      "opaa.auth.local.admin-allowed-cidrs=127.0.0.1/32,10.0.0.0/8",
      "opaa.features.external-access.enabled=false"
    })
public @interface OpaaLocalAuthSeedTest {

  /** A deliverable-looking address, deliberately not the refused default. */
  String INITIAL_ADMIN_EMAIL = "systemverwaltung@opaa.test.example";
}
