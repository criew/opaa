package io.opaa.observability;

/**
 * A health group whose probe calls no foreign service, only this process, its database or a state
 * held in memory: {@link HealthGroupMetricsConfiguration} reads it every {@link
 * HealthGroupMetricsConfiguration#LOCAL_INTERVAL}. A group without this mark is read only every
 * {@link HealthGroupMetricsConfiguration#INTERVAL}, so a new group that calls a service stays slow
 * unless it declares otherwise.
 */
public interface LocalHealthGroup {

  /** The name of the health group. */
  String healthGroup();
}
