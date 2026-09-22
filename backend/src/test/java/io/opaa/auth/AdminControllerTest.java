package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.local.LocalAdminAvailabilityGuard;
import io.opaa.common.ConflictException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
  private static final String REGULAR_USER_SUBJECT = "test-subject-regular";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;

  private final UUID actingAdminId = UUID.randomUUID();
  private final UUID actingAdminOrganizationId = UUID.randomUUID();

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
        .jwt(builder -> builder.subject(REGULAR_USER_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  private User actingAdmin;

  @BeforeEach
  void setUp() {
    actingAdmin = new User(TEST_SUBJECT, TEST_ISSUER, "admin@example.com", "Admin");
    actingAdmin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    actingAdmin.setOrganizationId(actingAdminOrganizationId);
    setId(actingAdmin, actingAdminId);
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && TEST_SUBJECT.equals(token.getSubject()))))
        .thenReturn(actingAdmin);

    // UserProvisioningFilter (wired via AdminTestSecurityConfig) re-derives authorities from the
    // provisioned User's real SystemRole - a distinct subject/user is required so that enrichment
    // does not silently grant ROLE_SYSTEM_ADMIN to a request meant to simulate a regular caller.
    User regularUser = new User(REGULAR_USER_SUBJECT, TEST_ISSUER, "user@example.com", "User");
    regularUser.setSystemRole(SystemRole.USER);
    regularUser.setOrganizationId(UUID.randomUUID());
    setId(regularUser, UUID.randomUUID());
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> REGULAR_USER_SUBJECT.equals(token.getSubject()))))
        .thenReturn(regularUser);
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
    when(userService.findAllInOrganization(actingAdminOrganizationId)).thenReturn(List.of(user));

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
    when(userService.updateRole(userId, SystemRole.SYSTEM_ADMIN, CurrentUser.from(actingAdmin)))
        .thenReturn(user);

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

  /**
   * The lockout guard's refusal (#1349) reaches the client as 409 with its stable code and the
   * German sentence - the code is what the frontend acts on, the message what it shows.
   */
  @Test
  void changeRoleRefusedByTheLockoutGuardReturns409WithItsCode() throws Exception {
    UUID userId = UUID.randomUUID();
    String refusal = "Der letzte anmeldefähige Systemverwalter kann nicht entfernt werden.";
    when(userService.updateRole(any(), any(), any()))
        .thenThrow(new ConflictException(refusal, LocalAdminAvailabilityGuard.ERROR_CODE));

    mockMvc
        .perform(
            post("/api/v1/admin/users/" + userId + "/role")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"USER\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(LocalAdminAvailabilityGuard.ERROR_CODE))
        .andExpect(jsonPath("$.error").value(refusal));
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
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Benutzer nicht gefunden: " + userId))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  /**
   * #1799: the controller-local branch attached its body unconditionally, so an {@code Accept} that
   * excludes JSON made the writer raise a second exception, the response was discarded and the
   * caller got the container's 500 instead of this 404. The tap is on the ROOT logger - the
   * stacktrace comes from a Spring logger, a tap on the handler's own would not see it (the pattern
   * of {@code GlobalExceptionHandlerUnwritableAcceptTest}).
   */
  @Test
  void changeRoleForNonexistentUserWithAnUnwritableAcceptKeepsIts404AndLogsNoStacktrace()
      throws Exception {
    UUID userId = UUID.randomUUID();
    when(userService.updateRole(any(), any(), any()))
        .thenThrow(new UserNotFoundException("Benutzer nicht gefunden: " + userId));

    Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    logAppender.start();
    rootLogger.addAppender(logAppender);
    try {
      mockMvc
          .perform(
              post("/api/v1/admin/users/" + userId + "/role")
                  .with(asAdmin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"role\": \"SYSTEM_ADMIN\"}")
                  .accept(MediaType.APPLICATION_XML))
          .andExpect(status().isNotFound())
          .andExpect(content().string(""));

      assertThat(logAppender.list)
          .as("a caller error must not put a stacktrace into the log, whoever logs it")
          .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    } finally {
      rootLogger.detachAppender(logAppender);
    }
  }
}
