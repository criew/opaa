package io.opaa.compliance;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks configuration keys whose defaults bear neither on data nor on visibility, evaluation or
 * data flows (ADR-0052, Entscheidung 9): technical bounds, timeouts, budgets, rate limits, search
 * tuning, secrets without a default.
 *
 * <p>On a type it covers every key the configuration metadata attributes to the type and its nested
 * types. On a {@code @ConfigurationProperties} type that blanket does not cover a switch, a model,
 * an address, a period or an interval (a key ending in {@code enabled}, {@code model}, {@code url},
 * {@code uri}, {@code endpoint}, {@code days}, {@code months}, {@code hours}, {@code minutes} or
 * {@code ttl}, or naming a lifetime, retention or interval), nor any component of type {@code
 * Duration} or {@code Period}; such a key carries its own marker on its component.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface NoDataImpact {

  /** Why no default of the marked keys bears on data, in a few English words. */
  String value();
}
