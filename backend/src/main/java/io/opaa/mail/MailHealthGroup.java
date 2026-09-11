package io.opaa.mail;

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
 * Keeps the {@code mail} contributor out of the overall health status and shows it in its own
 * group, {@code /actuator/health/mail} (#1536): {@code /actuator/health} is the documented
 * container health check, and a single refused mail must not take an instance out of rotation while
 * chat and search keep working. The same post-processor pattern {@code UploadStoreHealthGroup} uses
 * for the same reason (ADR-0030, Entscheidung 9); the group borrows the primary group's visibility
 * and status rules.
 */
final class MailHealthGroup implements HealthEndpointGroupsPostProcessor {

  /**
   * Contributor and group must not share a name - Spring Boot refuses to register a contributor
   * whose name collides with a group ("clashes with group"), the same reason {@code
   * UploadStoreHealthGroup} pairs {@code uploadStore} with {@code upload-store}.
   */
  static final String CONTRIBUTOR = "mailDelivery";

  static final String GROUP = "mail";

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
