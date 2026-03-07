package io.opaa.auth;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminController.class)
@ActiveProfiles("basic")
@Import(AdminTestSecurityConfig.class)
class AdminControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;

  @Test
  @WithMockUser(roles = "SYSTEM_ADMIN")
  void listUsersAsAdminReturnsUsers() throws Exception {
    User user = new User("sub1", "issuer1", "test@example.com", "Test User");
    when(userService.findAll()).thenReturn(List.of(user));

    mockMvc
        .perform(get("/api/v1/admin/users"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").value("test@example.com"))
        .andExpect(jsonPath("$[0].displayName").value("Test User"))
        .andExpect(jsonPath("$[0].systemRole").value("USER"));
  }

  @Test
  @WithMockUser(roles = "USER")
  void listUsersAsRegularUserReturns403() throws Exception {
    mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "SYSTEM_ADMIN")
  void changeRoleAsAdminSucceeds() throws Exception {
    UUID userId = UUID.randomUUID();
    User user = new User("sub1", "issuer1", "test@example.com", "Test User");
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(userService.updateRole(userId, SystemRole.SYSTEM_ADMIN)).thenReturn(user);

    mockMvc
        .perform(
            post("/api/v1/admin/users/" + userId + "/role")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"SYSTEM_ADMIN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.systemRole").value("SYSTEM_ADMIN"));
  }

  @Test
  @WithMockUser(roles = "USER")
  void changeRoleAsRegularUserReturns403() throws Exception {
    UUID userId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/admin/users/" + userId + "/role")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"SYSTEM_ADMIN\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "SYSTEM_ADMIN")
  void changeRoleForNonexistentUserReturns404() throws Exception {
    UUID userId = UUID.randomUUID();
    when(userService.updateRole(userId, SystemRole.SYSTEM_ADMIN))
        .thenThrow(new UserNotFoundException("User not found: " + userId));

    mockMvc
        .perform(
            post("/api/v1/admin/users/" + userId + "/role")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"SYSTEM_ADMIN\"}"))
        .andExpect(status().isNotFound());
  }
}
