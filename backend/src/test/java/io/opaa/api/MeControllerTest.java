package io.opaa.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.group.GroupKind;
import io.opaa.group.GroupService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code GET /api/v1/me/groups} carries no {@code @PreAuthorize}, unlike every endpoint on {@link
 * io.opaa.group.GroupController}, exactly so a regular user can reach their own memberships (see
 * {@code GroupService#listMyGroups}'s Javadoc). A test against {@code GroupService} directly cannot
 * prove that: {@code @PreAuthorize} is enforced by the Spring Security AOP proxy in front of the
 * controller method, not inside the service. This test exercises the actual endpoint with a {@code
 * ROLE_USER} authority (no {@code SYSTEM_ADMIN}) so that adding a restriction back to {@link
 * MeController#myGroups} - the regression this endpoint exists to prevent - would fail it.
 */
@WebMvcTest(MeController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class MeControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private GroupService groupService;
  @MockitoBean private UserService userService;

  private User user;

  @BeforeEach
  void setUp() {
    user = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    user.setSystemRole(SystemRole.USER);
    when(userService.findBySubjectAndIssuer(TEST_SUBJECT, TEST_ISSUER))
        .thenReturn(Optional.of(user));
  }

  private RequestPostProcessor asRegularUser() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @Test
  void myGroupsSucceedsForARegularUserWithoutTheSystemAdminRole() throws Exception {
    UUID groupId = UUID.randomUUID();
    GroupListResponse group =
        new GroupListResponse(groupId, "Team A", GroupKind.AD_HOC, 2, Instant.now(), Instant.now());
    when(groupService.listMyGroups(user.getId())).thenReturn(List.of(group));

    mockMvc
        .perform(get("/api/v1/me/groups").with(asRegularUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(groupId.toString()))
        .andExpect(jsonPath("$[0].name").value("Team A"));
  }

  @Test
  void myGroupsReturnsAnEmptyListForARegularUserWithoutAnyMembership() throws Exception {
    when(groupService.listMyGroups(user.getId())).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/me/groups").with(asRegularUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }
}
