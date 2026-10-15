package io.opaa.compliance;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a configuration key whose default bears on data, visibility, evaluation or data flows
 * (ADR-0052, Entscheidung 9): its default is a {@code settings} entry of the compliance manifest,
 * and a changed default fails the build until that entry follows.
 *
 * <p>On a component of a {@code @ConfigurationProperties} record it marks that key; on a type it
 * marks every key the configuration metadata attributes to the type and its nested types, unless a
 * component says otherwise.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface DataImpact {}
