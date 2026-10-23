package io.opaa.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.GlobalExceptionHandler;
import io.opaa.knowledge.UploadProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link RequiresRecentLogin} at a probe endpoint, the way an operation such as the self-disclosure
 * wears it: marked on the method or on the controller, the handler runs only for a recent sign-in,
 * and every other token gets the {@code 403} envelope with code and reason from {@code
 * GlobalExceptionHandler}; an unmarked handler is untouched. Whether the interceptor is registered
 * in the application is {@code LocalReauthenticationIntegrationTest}'s subject.
 */
class RecentLoginInterceptorTest {

  private static final Clock CLOCK = Clock.systemUTC();

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new MarkedMethodProbe(), new MarkedControllerProbe())
          .addInterceptors(
              new RecentLoginInterceptor(
                  new RecentLoginPolicy(new RecentLoginProperties(Duration.ofMinutes(5)), CLOCK)))
          .setControllerAdvice(
              new GlobalExceptionHandler(
                  new UploadProperties(null, null, 52_428_800L, null, 0), CLOCK))
          .build();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aRecentSignInReachesTheMarkedOperation() throws Exception {
    authenticate(Instant.now().minusSeconds(30));

    mockMvc.perform(get("/probe/marked")).andExpect(status().isOk());
    mockMvc.perform(get("/probe/controller")).andExpect(status().isOk());
  }

  @Test
  void anOldSignInIsTurnedAwayWithCodeAndReason() throws Exception {
    authenticate(Instant.now().minus(Duration.ofMinutes(6)));

    mockMvc
        .perform(get("/probe/marked"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.code").value(RecentLoginPolicy.CODE))
        .andExpect(jsonPath("$.reason").value("AUTH_TIME_TOO_OLD"))
        .andExpect(jsonPath("$.error").value(RecentLoginPolicy.MESSAGE));
    mockMvc
        .perform(get("/probe/controller"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(RecentLoginPolicy.CODE));
  }

  @Test
  void aTokenWithoutASignInIsTurnedAwayButReachesWhatIsNotMarked() throws Exception {
    authenticate(null);

    mockMvc
        .perform(get("/probe/marked"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.reason").value("AUTH_TIME_MISSING"));
    mockMvc.perform(get("/probe/unmarked")).andExpect(status().isOk());
  }

  private static void authenticate(Instant authTime) {
    Map<String, Object> claims =
        authTime == null
            ? Map.of("sub", "erika")
            : Map.of("sub", "erika", RecentLoginPolicy.AUTH_TIME_CLAIM, authTime.getEpochSecond());
    Instant now = Instant.now();
    Jwt jwt = new Jwt("t", now, now.plusSeconds(600), Map.of("alg", "none"), claims);
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
  }

  /**
   * {@code @Profile} on a profile no context activates: every {@code @SpringBootTest} context scans
   * {@code io.opaa} including the test classes; {@code standaloneSetup} registers these instances
   * directly.
   */
  @RestController
  @Profile("standalone-probe")
  static class MarkedMethodProbe {

    @RequiresRecentLogin
    @GetMapping("/probe/marked")
    String marked() {
      return "ok";
    }

    @GetMapping("/probe/unmarked")
    String unmarked() {
      return "ok";
    }
  }

  @RestController
  @Profile("standalone-probe")
  @RequiresRecentLogin
  static class MarkedControllerProbe {

    @GetMapping("/probe/controller")
    String any() {
      return "ok";
    }
  }
}
