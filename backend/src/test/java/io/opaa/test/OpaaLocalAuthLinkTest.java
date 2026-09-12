package io.opaa.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link OpaaLocalAuthMockMvcTest} plus the two properties the link flows need (ADR-0033,
 * Entscheidung 10): a public base URL and the AES key under which the SMTP password is stored.
 *
 * <p><b>Why this cannot be the base signature:</b> without {@code opaa.public-base-url} the
 * invitation, reset and registration flows are switched off by design, and classes on the base
 * signature assert exactly that - that the switches refuse to turn on and the public configuration
 * reports the flows as disabled. Setting the base URL globally would make those assertions
 * untestable. The {@code oidc} profile ships no settings encryption key either (only {@code dev}
 * does), so a class that stores an SMTP password needs one.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OpaaLocalAuthMockMvcTest
@TestPropertySource(
    properties = {
      "opaa.public-base-url=https://opaa.test.example",
      "opaa.security.settings.encryption-key=c2V0dGluZ3NkZXZrZXkwMHNldHRpbmdzZGV2a2V5MDE="
    })
public @interface OpaaLocalAuthLinkTest {}
