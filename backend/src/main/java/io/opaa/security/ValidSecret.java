package io.opaa.security;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A secret that is present and strong: at least {@value SecretValidator#MIN_LENGTH} characters and
 * none of the well-known placeholders an example configuration ships with (ADR-0033, Entscheidung
 * 6). Enforced by {@code io.opaa.auth.local.LocalAuthSecretGuard} at startup.
 */
@Documented
@Constraint(validatedBy = SecretValidator.class)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSecret {

  String message() default
      "must be a strong, non-placeholder secret of at least 32 characters (generate one with:"
          + " openssl rand -base64 48)";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
