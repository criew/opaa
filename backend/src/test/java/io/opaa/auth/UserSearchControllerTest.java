package io.opaa.auth;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * {@code GET /api/v1/users} carries no {@code @PreAuthorize}, unlike {@code
 * AdminController#listUsers}'s {@code GET /api/v1/admin/users} (#777). This test exercises the
 * actual endpoint with a plain {@code ROLE_USER} authority (no {@code SYSTEM_ADMIN}) - the same way
 * {@code MeControllerTest#myGroupsSucceedsForARegularUserWithoutTheSystemAdminRole} proves {@code
 * GET /api/v1/me/groups} is reachable for a regular user - so that adding a {@code SYSTEM_ADMIN}
 * restriction back to {@link UserSearchController#listUsers} would fail it. Before this endpoint
 * existed, every member/grant picker in the frontend called the admin-only list and silently
 * rendered an empty, dead search field for exactly this caller.
 */
@WebMvcTest(UserSearchController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class UserSearchControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;

  private final UUID actingUserId = UUID.randomUUID();
  private final UUID actingUserOrganizationId = UUID.randomUUID();

  private User actingUser;

  @BeforeEach
  void setUp() {
    actingUser = new User(TEST_SUBJECT, TEST_ISSUER, "user@example.com", "Regular User");
    actingUser.setSystemRole(SystemRole.USER);
    actingUser.setOrganizationId(actingUserOrganizationId);
    setId(actingUser, actingUserId);
    when(userService.findBySubjectAndIssuer(TEST_SUBJECT, TEST_ISSUER))
        .thenReturn(Optional.of(actingUser));
  }

  private void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private RequestPostProcessor asRegularUser() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @Test
  void listUsersSucceedsForARegularUserWithoutTheSystemAdminRole() throws Exception {
    User other = new User("sub1", "issuer1", "colleague@example.com", "Colleague");
    when(userService.searchInOrganization(actingUserOrganizationId, "Coll"))
        .thenReturn(List.of(other));

    mockMvc
        .perform(get("/api/v1/users").param("query", "Coll").with(asRegularUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").value("colleague@example.com"))
        .andExpect(jsonPath("$[0].displayName").value("Colleague"));
  }

  @Test
  void listUsersResponseCarriesNoSystemRoleField() throws Exception {
    User other = new User("sub1", "issuer1", "colleague@example.com", "Colleague");
    other.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(userService.searchInOrganization(actingUserOrganizationId, "Coll"))
        .thenReturn(List.of(other));

    mockMvc
        .perform(get("/api/v1/users").param("query", "Coll").with(asRegularUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].systemRole").doesNotExist());
  }

  // #778 review, finding 4: the controller passes the raw query parameter straight through to
  // UserService#searchInOrganization, which owns the minimum-length/empty-query behaviour - this
  // just proves the controller does not short-circuit or default it itself.
  @Test
  void listUsersPassesAMissingQueryThroughToTheService() throws Exception {
    when(userService.searchInOrganization(actingUserOrganizationId, null)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/users").with(asRegularUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }
}
