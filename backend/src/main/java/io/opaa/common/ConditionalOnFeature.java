package io.opaa.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Registers the annotated class or {@code @Bean} method only while the installation-wide feature is
 * switched on (ADR-0044); a missing {@code opaa.features.<key>.enabled} counts as on.
 *
 * <p>Use it for leaf beans nothing else injects: controllers, schedulers, runners, listeners. A
 * bean other beans depend on stays registered and asks {@link FeatureFlags#isEnabled(Feature)} at
 * runtime instead - removing it would break the start of every bean that needs it.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnFeatureCondition.class)
public @interface ConditionalOnFeature {

  /** The feature that must be switched on. */
  Feature value();
}
