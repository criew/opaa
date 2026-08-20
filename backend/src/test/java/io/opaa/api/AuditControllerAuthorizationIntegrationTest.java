package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.TestcontainersConfiguration;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PR #450 review, finding 6: {@code AuditControllerTest} (the {@code @WebMvcTest} slice) mocks
 * {@code AuditQueryService} entirely, so its 403 test stubs {@code AccessDeniedException} rather
 * than proving the real authorization decision - correct for that slice's purpose (see its own
 * Javadoc), but it means no test exercises the real {@code AuditQueryService#requireAuditor} check
 * through the actual HTTP endpoint. This class closes that gap the same way {@link
 * LibraryIndexingAuthorizationIntegrationTest} already does for {@code POST
 * /api/v1/libraries/{libraryId}/indexing}: full Spring context, the real {@code dev} security chain
 * ({@link DevAuthFilter}, {@code UserProvisioningFilter}, and - since #394 - the real role check
 * inside {@code AuditQueryService} itself rather than {@code @PreAuthorize}) against a real
 * Postgres, so both the 403 for a plain USER and the 200 for an AUDITOR come from production code.
 *
 * <p>Uses the shared {@link TestcontainersConfiguration} rather than its own
 * {@code @Container}/{@code @DynamicPropertySource} (issue #497, measure 5), so it shares one
 * cached context and one container with {@code BrandingControllerIntegrationTest} and {@code
 * LibraryControllerCredentialsIntegrationTest} - all three now carry the identical
 * {@code @SpringBootTest}/{@code @AutoConfigureMockMvc}/{@code @Import(TestcontainersConfiguration.class)}/{@code @ActiveProfiles("dev")}
 * signature.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class AuditControllerAuthorizationIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
  private final Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

  @AfterEach
  void tearDown() {
    // Reset the role rather than deleting the row: dev-user provisioning also creates a default
    // personal space (fk_spaces_owner, RESTRICT) - a delete here would fail with a foreign key
    // violation instead of cleaning up, and the next test would then run against whatever role
    // this test left behind (which is exactly what a failed delete did before this fix).
    jdbcTemplate.update(
        "UPDATE users SET system_role = 'USER' WHERE email = 'dev-user@opaa.local'");
  }

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      return request;
    };
  }

  /**
   * A real, plain {@code USER} (the seeded {@code dev-user}, provisioned by the real {@link
   * io.opaa.auth.UserProvisioningFilter} on this very request) hitting the real {@code
   * AuditController} endpoint is rejected by the real {@code AuditQueryService#requireAuditor}
   * check - no mock anywhere in this call chain.
   */
  @Test
  void aRealNonAuditorUserGetsForbiddenFromTheRealEndpoint() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit/events/by-time-range")
                .with(devUser("dev-user"))
                .param("from", from.toString())
                .param("to", to.toString())
                .param("reason", "PR #450 review, finding 6"))
        .andExpect(status().isForbidden());
  }

  /**
   * The same real chain, but for a user actually holding the AUDITOR role - promoted directly via
   * {@link UserRepository} after first provisioning (the same pattern {@code
   * IndexingControllerAuthorizationIntegrationTest} uses to set up a fixture role/grant), not
   * through any mock. Proves the real check also lets the right caller through, not merely that it
   * rejects everyone.
   */
  @Test
  void aRealAuditorUserSucceedsAgainstTheRealEndpoint() throws Exception {
    mockMvc.perform(get("/api/v1/audit/events/by-time-range").with(devUser("dev-user")));
    User devUser =
        userRepository.findAll().stream()
            .filter(u -> "dev-user@opaa.local".equals(u.getEmail()))
            .findFirst()
            .orElseThrow();
    devUser.setSystemRole(SystemRole.AUDITOR);
    userRepository.save(devUser);

    mockMvc
        .perform(
            get("/api/v1/audit/events/by-time-range")
                .with(devUser("dev-user"))
                .param("from", from.toString())
                .param("to", to.toString())
                .param("reason", "PR #450 review, finding 6"))
        .andExpect(status().isOk());

    assertThat(devUser.getSystemRole()).isEqualTo(SystemRole.AUDITOR);
  }
}
