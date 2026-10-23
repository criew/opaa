package io.opaa.chat.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.ChatCleanupAfterLockStatus;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.chat.LockedAccountChatCleanup;
import io.opaa.chat.LockedAccountChatCleanupService;
import io.opaa.common.NotFoundException;
import java.time.Instant;
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
 * {@link LocalUserChatCleanupController} in isolation: {@code SYSTEM_ADMIN} only, the response
 * shape, and 404 for an account the domain does not know as a local one of the organization.
 */
@WebMvcTest(LocalUserChatCleanupController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class LocalUserChatCleanupControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String ADMIN_SUBJECT = "admin-subject";
  private static final String USER_SUBJECT = "user-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private LockedAccountChatCleanupService cleanupService;
  @MockitoBean private UserService userService;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID accountId = UUID.randomUUID();
  private final String path = "/api/v1/admin/local-users/" + accountId + "/chat-cleanup";

  @BeforeEach
  void setUp() {
    User admin = new User(ADMIN_SUBJECT, TEST_ISSUER, "admin@example.com", "Admin");
    admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    admin.setOrganizationId(organizationId);
    setId(admin, UUID.randomUUID());
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && ADMIN_SUBJECT.equals(token.getSubject()))))
        .thenReturn(admin);
    User regular = new User(USER_SUBJECT, TEST_ISSUER, "user@example.com", "User");
    regular.setOrganizationId(organizationId);
    setId(regular, UUID.randomUUID());
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && USER_SUBJECT.equals(token.getSubject()))))
        .thenReturn(regular);
  }

  @Test
  void aRegularUserIsRejectedOnEveryOperation() throws Exception {
    mockMvc.perform(get(path).with(asUser())).andExpect(status().isForbidden());
    mockMvc.perform(put(path + "/hold").with(asUser())).andExpect(status().isForbidden());
    mockMvc.perform(delete(path + "/hold").with(asUser())).andExpect(status().isForbidden());
    verifyNoInteractions(cleanupService);
  }

  @Test
  void theReadNamesTheEarliestDeletionOfAnAccountLockedByTheAdministration() throws Exception {
    Instant due = Instant.parse("2027-10-23T08:00:00Z");
    when(cleanupService.stateOf(organizationId, accountId))
        .thenReturn(
            new LockedAccountChatCleanup(ChatCleanupAfterLockStatus.SCHEDULED, false, 365, due));

    mockMvc
        .perform(get(path).with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"))
        .andExpect(jsonPath("$.held").value(false))
        .andExpect(jsonPath("$.deleteAfterDays").value(365))
        .andExpect(jsonPath("$.earliestDeletionAt").value("2027-10-23T08:00:00Z"));
  }

  @Test
  void holdAndResumeAnswerWithTheNewState() throws Exception {
    when(cleanupService.hold(any(), eq(accountId)))
        .thenReturn(new LockedAccountChatCleanup(ChatCleanupAfterLockStatus.HELD, true, 365, null));
    when(cleanupService.resume(any(), eq(accountId)))
        .thenReturn(
            new LockedAccountChatCleanup(ChatCleanupAfterLockStatus.NOT_LOCKED, false, 365, null));

    mockMvc
        .perform(put(path + "/hold").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("HELD"))
        .andExpect(jsonPath("$.held").value(true));
    mockMvc
        .perform(delete(path + "/hold").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NOT_LOCKED"));
    verify(cleanupService).hold(any(), eq(accountId));
    verify(cleanupService).resume(any(), eq(accountId));
  }

  @Test
  void anAccountUnknownAsALocalOneIsNotFound() throws Exception {
    when(cleanupService.stateOf(organizationId, accountId))
        .thenThrow(new NotFoundException("Konto nicht gefunden"));

    mockMvc.perform(get(path).with(asAdmin())).andExpect(status().isNotFound());
  }

  private RequestPostProcessor asAdmin() {
    return jwt()
        .jwt(builder -> builder.subject(ADMIN_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
  }

  private RequestPostProcessor asUser() {
    return jwt()
        .jwt(builder -> builder.subject(USER_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  private static void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
