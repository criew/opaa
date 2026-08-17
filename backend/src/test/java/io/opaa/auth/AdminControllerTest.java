package io.opaa.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(AdminController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class AdminControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;

  private final UUID actingAdminId = UUID.randomUUID();

  /**
   * #392 code review, finding 3: {@code AdminController#changeRole} now resolves the acting person
   * via {@code @AuthenticationPrincipal Jwt}, the same as {@code LibraryControllerDocumentTest}'s
   * {@code asTestUser()} - {@code @WithMockUser} (used here before that change) supplies a plain
   * {@code UsernamePasswordAuthenticationToken}, which leaves {@code @AuthenticationPrincipal Jwt}
   * {@code null} and would NPE inside {@code currentUser(jwt)}. {@code jwt()}'s authorities are set
   * explicitly since {@code AdminTestSecurityConfig} configures no JWT-to-authority converter of
   * its own - {@code SecurityMockMvcRequestPostProcessors.jwt()}'s own default ({@code
   * SCOPE_}-prefixed) authorities would not satisfy
   * {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")}.
   */
  private RequestPostProcessor asAdmin() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
  }

  private RequestPostProcessor asRegularUser() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @BeforeEach
  void setUp() {
    User actingAdmin = new User(TEST_SUBJECT, TEST_ISSUER, "admin@example.com", "Admin");
    actingAdmin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    setId(actingAdmin, actingAdminId);
    when(userService.findBySubjectAndIssuer(TEST_SUBJECT, TEST_ISSUER))
        .thenReturn(Optional.of(actingAdmin));
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

  @Test
  void listUsersAsAdminReturnsUsers() throws Exception {
    User user = new User("sub1", "issuer1", "test@example.com", "Test User");
    when(userService.findAll()).thenReturn(List.of(user));

    mockMvc
        .perform(get("/api/v1/admin/users").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").value("test@example.com"))
        .andExpect(jsonPath("$[0].displayName").value("Test User"))
        .andExpect(jsonPath("$[0].systemRole").value("USER"));
  }

  @Test
  void listUsersAsRegularUserReturns403() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/users").with(asRegularUser()))
        .andExpect(status().isForbidden());
  }

  @Test
  void changeRoleAsAdminSucceeds() throws Exception {
    UUID userId = UUID.randomUUID();
    User user = new User("sub1", "issuer1", "test@example.com", "Test User");
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(userService.updateRole(userId, SystemRole.SYSTEM_ADMIN, actingAdminId)).thenReturn(user);

    mockMvc
        .perform(
            post("/api/v1/admin/users/" + userId + "/role")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"SYSTEM_ADMIN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.systemRole").value("SYSTEM_ADMIN"));
  }

  @Test
  void changeRoleAsRegularUserReturns403() throws Exception {
    UUID userId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/admin/users/" + userId + "/role")
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"SYSTEM_ADMIN\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void changeRoleForNonexistentUserReturns404() throws Exception {
    UUID userId = UUID.randomUUID();
    when(userService.updateRole(any(), any(), any()))
        .thenThrow(new UserNotFoundException("Benutzer nicht gefunden: " + userId));

    mockMvc
        .perform(
            post("/api/v1/admin/users/" + userId + "/role")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"SYSTEM_ADMIN\"}"))
        .andExpect(status().isNotFound());
  }
}
