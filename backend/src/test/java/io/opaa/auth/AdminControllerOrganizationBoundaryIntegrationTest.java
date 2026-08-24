package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaMockMvcTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * PR #679 review, finding 1: {@code UserServiceOrganizationBoundaryIntegrationTest} proves the
 * organization boundary at the {@link UserService} layer, and {@code AdminControllerTest} (a
 * {@code @WebMvcTest} slice) proves that a {@link UserNotFoundException} maps to an HTTP 404 - but
 * with {@code UserService} mocked out, no test ever ran a foreign-organization id through the real
 * {@code POST /api/v1/admin/users/{id}/role} endpoint start to finish. This class closes that gap
 * the same way {@code AuditControllerAuthorizationIntegrationTest} closes the equivalent one for
 * {@code AuditQueryService#requireAuditor}: full Spring context, the real {@code dev} security
 * chain ({@link io.opaa.auth.DevAuthFilter}, {@code UserProvisioningFilter}) and the real {@link
 * UserService}/{@link UserRepository} against a real Postgres, so both the 404 for a
 * foreign-organization target and the 200 for a same-organization target come from production code,
 * not a stub.
 *
 * <p>{@code dev-admin} (subject {@code dev-admin}, email {@code admin@opaa.local}) is provisioned
 * as {@code SYSTEM_ADMIN} on first request because its email matches {@code
 * opaa.auth.initial-admin-email} (see {@code application.yml}, {@code dev} profile) - the same
 * seeding {@code AuditControllerAuthorizationIntegrationTest} relies on for {@code dev-user}. That
 * first request also provisions {@code dev-admin} into {@link Organization#DEFAULT_ID}, which this
 * class never touches or deletes (other tests sharing this Spring context depend on that row
 * existing, see {@code SpaceServiceIntegrationTest}'s identical reasoning) - only the throwaway
 * second organization and its one throwaway user are created and torn down per test.
 */
@OpaaMockMvcTest
class AdminControllerOrganizationBoundaryIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID otherOrganizationId;

  @AfterEach
  void tearDown() {
    if (otherOrganizationId != null) {
      userRepository.deleteAll(userRepository.findByOrganizationId(otherOrganizationId));
      jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", otherOrganizationId);
      organizationRepository.deleteById(otherOrganizationId);
      otherOrganizationId = null;
    }
  }

  private RequestPostProcessor devAdmin() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-admin");
      return request;
    };
  }

  private UUID createUserInOtherOrganization() {
    otherOrganizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Other Org")).getId();
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "other-org@example.com", "Other");
    user.setOrganizationId(otherOrganizationId);
    return userRepository.save(user).getId();
  }

  /**
   * The reproduction case the review names: a real SYSTEM_ADMIN in {@link Organization#DEFAULT_ID}
   * targeting a real user that only exists in a different organization must be rejected as 404, end
   * to end through the real endpoint - not merely at the service layer or the exception-mapping
   * layer in isolation.
   */
  @Test
  void changingTheRoleOfAUserInAnotherOrganizationReturns404ThroughTheRealEndpoint()
      throws Exception {
    // Provisions dev-admin as SYSTEM_ADMIN in Organization.DEFAULT_ID on this first request.
    mockMvc.perform(get("/api/v1/admin/users").with(devAdmin())).andExpect(status().isOk());
    UUID targetUserId = createUserInOtherOrganization();

    mockMvc
        .perform(
            post("/api/v1/admin/users/" + targetUserId + "/role")
                .with(devAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"SYSTEM_ADMIN\"}"))
        .andExpect(status().isNotFound());

    assertThat(userRepository.findById(targetUserId).orElseThrow().getSystemRole())
        .isEqualTo(SystemRole.USER);
  }

  /**
   * The positive counterpart: the same real chain must not reject a target user in the acting
   * admin's own organization - proving the 404 above comes from the organization check, not from
   * some unrelated regression that would reject every target.
   */
  @Test
  void changingTheRoleOfAUserInTheSameOrganizationSucceedsThroughTheRealEndpoint()
      throws Exception {
    mockMvc.perform(get("/api/v1/admin/users").with(devAdmin())).andExpect(status().isOk());
    User targetInDefaultOrganization =
        new User(UUID.randomUUID().toString(), "test-issuer", "same-org@example.com", "Same");
    targetInDefaultOrganization.setOrganizationId(Organization.DEFAULT_ID);
    UUID targetUserId = userRepository.save(targetInDefaultOrganization).getId();

    try {
      mockMvc
          .perform(
              post("/api/v1/admin/users/" + targetUserId + "/role")
                  .with(devAdmin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"role\": \"AUDITOR\"}"))
          .andExpect(status().isOk());

      assertThat(userRepository.findById(targetUserId).orElseThrow().getSystemRole())
          .isEqualTo(SystemRole.AUDITOR);
    } finally {
      // fk_audit_actor_pseudonyms_user is ON DELETE CASCADE (migration 017) - deleting the user
      // removes its pseudonym mapping automatically; audit_log itself has no foreign key back to
      // users (only to organizations), so the immutable log rows this test wrote simply remain,
      // scoped to Organization.DEFAULT_ID like every other test sharing this context.
      userRepository.deleteById(targetUserId);
    }
  }
}
