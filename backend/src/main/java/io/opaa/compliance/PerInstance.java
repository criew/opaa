package io.opaa.compliance;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks configuration keys whose value may differ between the instances of an installation
 * (ADR-0053, Entscheidung 4): bounds, timeouts, pool sizes and budgets of the one process. They
 * stay out of the configuration fingerprint.
 *
 * <p>On a type it covers every key the configuration metadata attributes to the type and its nested
 * types, unless a component carries {@link InstallationWide}. A key with {@link DataImpact} is
 * installation-wide whatever this marker says.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface PerInstance {

  /** Why the marked keys may differ between instances, in a few English words. */
  String value();
}
