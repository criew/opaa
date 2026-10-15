package io.opaa.compliance;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that builds or wraps an outbound client for others without a target of its own: the
 * target comes from its caller. Every class that calls it carries {@link DataFlow}, so each target
 * stays anchored where it is decided.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DataFlowCarrier {}
