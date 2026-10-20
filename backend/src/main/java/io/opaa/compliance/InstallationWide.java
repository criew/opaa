package io.opaa.compliance;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks configuration keys that must hold the same value on every instance of an installation
 * (ADR-0053, Entscheidung 4): they enter the configuration fingerprint, and an instance whose value
 * differs from that of a live instance refuses to start. A key with {@link DataImpact} is
 * installation-wide without this marker.
 *
 * <p>On a component it marks that key; on a type every key the configuration metadata attributes to
 * the type and its nested types, unless a component carries {@link PerInstance}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface InstallationWide {

  /**
   * Whether the value is a secret: a refusal names only the key, never the value of this instance.
   */
  boolean secret() default false;
}
