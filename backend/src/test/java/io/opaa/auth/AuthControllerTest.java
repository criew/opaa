package io.opaa.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@ActiveProfiles("basic")
@Import(TestSecurityConfig.class)
class AuthControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AuthProperties authProperties;
  @MockitoBean private JwtTokenService jwtTokenService;
  @MockitoBean private UserService userService;
  @MockitoBean private PasswordEncoder passwordEncoder;

  @Test
  void loginWithValidCredentialsReturnsToken() throws Exception {
    when(authProperties.basic())
        .thenReturn(
            new AuthProperties.BasicAuth(
                List.of(new AuthProperties.BasicUser("admin", "admin")),
                "dummy-secret",
                3600,
                "opaa-basic"));
    when(jwtTokenService.generateToken(anyString(), anyString(), anyString()))
        .thenReturn("test-jwt-token");
    when(jwtTokenService.getExpirationSeconds()).thenReturn(3600L);
    when(userService.findOrCreateUser(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new User("admin", "opaa-basic", "admin@opaa.local", "admin"));

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"admin\", \"password\": \"admin\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("test-jwt-token"))
        .andExpect(jsonPath("$.expiresIn").value(3600));
  }

  @Test
  void loginWithInvalidCredentialsReturns401() throws Exception {
    when(authProperties.basic())
        .thenReturn(
            new AuthProperties.BasicAuth(
                List.of(new AuthProperties.BasicUser("admin", "admin")),
                "dummy-secret",
                3600,
                "opaa-basic"));

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"admin\", \"password\": \"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Invalid credentials"))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.timestamp").isNotEmpty());
  }

  @Test
  void loginSucceedsForSecondConfiguredUser() throws Exception {
    when(authProperties.basic())
        .thenReturn(
            new AuthProperties.BasicAuth(
                List.of(
                    new AuthProperties.BasicUser("admin", "admin"),
                    new AuthProperties.BasicUser("alice", "secret")),
                "dummy-secret",
                3600,
                "opaa-basic"));
    when(jwtTokenService.generateToken(anyString(), anyString(), anyString()))
        .thenReturn("alice-token");
    when(jwtTokenService.getExpirationSeconds()).thenReturn(3600L);
    when(userService.findOrCreateUser(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new User("alice", "opaa-basic", "alice@opaa.local", "alice"));

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"alice\", \"password\": \"secret\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("alice-token"));
  }

  @Test
  void loginWithMissingFieldsReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"\"}"))
        .andExpect(status().isBadRequest());
  }
}
