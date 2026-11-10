package io.opaa.observability;

/**
 * A health group whose probe calls no foreign service, only this process, its database or a state
 * held in memory: {@link HealthGroupMetricsConfiguration} reads it every {@link
 * HealthGroupMetricsConfiguration#LOCAL_INTERVAL}. The mark covers the whole group and holds only
 * if every contributor of the group is local. A group without this mark (and not passive) is read
 * only every {@link HealthGroupMetricsConfiguration#INTERVAL}, so a new group that calls a service
 * stays slow unless it declares otherwise.
 */
public interface LocalHealthGroup {

  /** The name of the health group. */
  String healthGroup();
}
