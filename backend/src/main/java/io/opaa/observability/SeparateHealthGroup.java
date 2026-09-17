package io.opaa.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;

/**
 * Takes the given contributors out of the overall health status and shows them in a group of their
 * own, {@code /actuator/health/<group>} (ADR-0030, Entscheidung 9): a foreign service that is not
 * reachable must not take an instance out of a load balancer's rotation while the rest of the
 * application keeps working. Spring Boot has no property that excludes a contributor from the
 * primary group, hence this post-processor; the group borrows the primary group's visibility and
 * status rules. Every other group - {@code liveness} and {@code readiness} included - keeps both
 * its members and its rules.
 *
 * <p>Contributor and group must not share a name: Spring Boot refuses to register a contributor
 * whose name collides with a group ("clashes with group"). One bean per group, so a contributor
 * whose indicator is switched off is not excluded either.
 */
public final class SeparateHealthGroup implements HealthEndpointGroupsPostProcessor {

  private final String group;
  private final Set<String> contributors;

  public SeparateHealthGroup(String group, Set<String> contributors) {
    this.group = group;
    this.contributors = Set.copyOf(contributors);
  }

  @Override
  public HealthEndpointGroups postProcessHealthEndpointGroups(HealthEndpointGroups groups) {
    HealthEndpointGroup primary = groups.getPrimary();
    Map<String, HealthEndpointGroup> named = new LinkedHashMap<>();
    for (String name : groups.getNames()) {
      named.put(name, groups.get(name));
    }
    named.put(group, new Membership(primary, contributors::contains, null));
    return HealthEndpointGroups.of(
        new Membership(
            primary, name -> !contributors.contains(name) && primary.isMember(name), primary),
        named);
  }

  /** {@code template}'s rules with {@code members} deciding membership. */
  private record Membership(
      HealthEndpointGroup template, Predicate<String> members, HealthEndpointGroup pathOwner)
      implements HealthEndpointGroup {

    @Override
    public boolean isMember(String name) {
      return members.test(name);
    }

    @Override
    public boolean showComponents(SecurityContext securityContext) {
      return template.showComponents(securityContext);
    }

    @Override
    public boolean showDetails(SecurityContext securityContext) {
      return template.showDetails(securityContext);
    }

    @Override
    public StatusAggregator getStatusAggregator() {
      return template.getStatusAggregator();
    }

    @Override
    public HttpCodeStatusMapper getHttpCodeStatusMapper() {
      return template.getHttpCodeStatusMapper();
    }

    @Override
    public AdditionalHealthEndpointPath getAdditionalPath() {
      return pathOwner == null ? null : pathOwner.getAdditionalPath();
    }
  }
}
