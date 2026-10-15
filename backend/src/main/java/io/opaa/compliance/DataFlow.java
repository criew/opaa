package io.opaa.compliance;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that sends data out of the installation ("ausgehender Datenfluss"): it builds a
 * client to a target outside OPAA, holds an outbound client bean or uses a {@link DataFlowCarrier}.
 * Each value is the id of a {@code dataFlows} entry of the {@link ComplianceManifest}, which holds
 * target, data, trigger and switch-off; the annotation only anchors the entry in the code. Nested
 * classes and lambdas count as their top-level class.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DataFlow {

  /** The ids of the {@code dataFlows} entries this class sends data through. */
  String[] value();
}
