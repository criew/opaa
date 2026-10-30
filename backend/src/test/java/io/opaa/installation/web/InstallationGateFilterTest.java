package io.opaa.installation.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.common.InstallationStatus;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which requests pass the installation mode: while pending only the installation API, the public
 * configuration of the interface and the health endpoints, everything else {@code 503}; once
 * completed everything but the installation API, which answers {@code 404}.
 */
class InstallationGateFilterTest {

  private final AtomicBoolean pending = new AtomicBoolean();
  private final InstallationStatus status = pending::get;
  private final InstallationGateFilter filter =
      new InstallationGateFilter(status, JsonMapper.builder().build(), Clock.systemUTC());

  @ParameterizedTest
  @CsvSource({
    "GET, /api/v1/installation, 200",
    "PUT, /api/v1/installation/steps/FIRST_ADMIN, 200",
    "POST, /api/v1/installation/complete, 200",
    "GET, /api/v1/auth/config, 200",
    "GET, /api/health, 200",
    "GET, /actuator/health, 200",
    "GET, /actuator/health/readiness, 200",
    "GET, /actuator/health/liveness, 200",
    "GET, /actuator/prometheus, 200",
    "GET, /api/v1/branding, 200",
    "GET, /api/v1/branding/login-background, 200",
    "PUT, /api/v1/branding, 503",
    "GET, /api/v1/spaces, 503",
    "POST, /api/v1/auth/local/login, 503",
    "GET, /api/v1/installationx, 503",
    "GET, /actuator/health/chat-model, 503",
    "POST, /mcp, 503"
  })
  void whilePending(String method, String path, int expected) throws Exception {
    pending.set(true);

    MockHttpServletResponse response = run(method, path);

    assertThat(response.getStatus()).isEqualTo(expected);
    if (expected == 503) {
      assertThat(response.getContentAsString()).contains("INSTALLATION_PENDING");
      assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
      assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }
  }

  @ParameterizedTest
  @CsvSource({
    "GET, /api/v1/installation, 404",
    "POST, /api/v1/installation/answer-file, 404",
    "POST, /api/v1/installation/steps/CHAT_MODEL/check, 404",
    "GET, /api/v1/spaces, 200",
    "POST, /api/v1/auth/local/login, 200",
    "GET, /api/v1/installationx, 200"
  })
  void onceCompleted(String method, String path, int expected) throws Exception {
    pending.set(false);

    assertThat(run(method, path).getStatus()).isEqualTo(expected);
  }

  private MockHttpServletResponse run(String method, String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  // regression guard: health answers without the database, so it can report DOWN instead of 500
  @Test
  void theOpenPathsNeverAskTheDatabase() throws Exception {
    InstallationGateFilter withoutDatabase =
        new InstallationGateFilter(
            () -> {
              throw new IllegalStateException("database down");
            },
            JsonMapper.builder().build(),
            Clock.systemUTC());

    for (String path :
        java.util.List.of("/actuator/health/readiness", "/actuator/health/liveness")) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      withoutDatabase.doFilter(
          new MockHttpServletRequest("GET", path), response, new MockFilterChain());
      assertThat(response.getStatus()).as(path).isEqualTo(200);
    }
  }
}
