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
 * <p><b>Why this cannot be the base signature:</b> {@code opaa.auth.initial-admin-email} carries
 * the shipped default {@code admin@opaa.local} there, which {@code LocalAdminSeeder} refuses
 * outright - and a class on the base signature asserts that refusal, the first thing an operator
 * meets after an update. An address that works and an address that is refused cannot be the same
 * property value. {@code opaa.auth.local.admin-allowed-cidrs} rides along: it is only meaningful
 * for the class that drives the seeded administrator's sign-in, and its own context already exists.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OpaaLocalAuthMockMvcTest
@TestPropertySource(
    properties = {
      "opaa.auth.initial-admin-email=" + OpaaLocalAuthSeedTest.INITIAL_ADMIN_EMAIL,
      "opaa.auth.local.admin-allowed-cidrs=127.0.0.1/32,10.0.0.0/8"
    })
public @interface OpaaLocalAuthSeedTest {

  /** A deliverable-looking address, deliberately not the refused default. */
  String INITIAL_ADMIN_EMAIL = "systemverwaltung@opaa.test.example";
}
