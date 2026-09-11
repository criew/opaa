package io.opaa.library;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;

/**
 * Keeps the {@code uploadStore} contributor out of the overall health status and shows it in its
 * own group, {@code /actuator/health/upload-store} (ADR-0030, Entscheidung 9): a store that is not
 * reachable must not take an instance out of a load balancer's rotation while chat and search keep
 * working. Spring Boot has no property that excludes a contributor from the primary group, hence
 * this post-processor; the group borrows the primary group's visibility and status rules.
 */
final class UploadStoreHealthGroup implements HealthEndpointGroupsPostProcessor {

  static final String CONTRIBUTOR = "uploadStore";
  static final String GROUP = "upload-store";

  @Override
  public HealthEndpointGroups postProcessHealthEndpointGroups(HealthEndpointGroups groups) {
    HealthEndpointGroup primary = groups.getPrimary();
    Map<String, HealthEndpointGroup> named = new LinkedHashMap<>();
    for (String name : groups.getNames()) {
      named.put(name, groups.get(name));
    }
    named.put(GROUP, new Membership(primary, CONTRIBUTOR::equals, null));
    return HealthEndpointGroups.of(
        new Membership(
            primary, name -> !CONTRIBUTOR.equals(name) && primary.isMember(name), primary),
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
