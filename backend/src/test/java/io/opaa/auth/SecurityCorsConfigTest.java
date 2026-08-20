package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * Regression coverage for #553: a browser PATCH (rename a chat, flip the useKnowledge switch) was
 * rejected with 403 by the CORS processing because PATCH was missing from the allowed methods.
 * Behind a TLS-terminating reverse proxy the backend sees a scheme mismatch and treats same-origin
 * browser requests as cross-origin, so the CORS check applies to every browser call.
 */
class SecurityCorsConfigTest {

  private static final String ALLOWED_ORIGIN = "https://opaa.example.org";

  private final CorsConfigurationSource source =
      new SecurityCorsConfig().corsConfigurationSource(ALLOWED_ORIGIN);

  @Test
  void actualCrossOriginPatchRequestIsAccepted() throws IOException {
    MockHttpServletRequest request = crossOriginRequest("PATCH");

    MockHttpServletResponse response = new MockHttpServletResponse();
    boolean accepted = process(request, response);

    assertThat(accepted).as("PATCH must pass the CORS check").isTrue();
    assertThat(response.getStatus()).isNotEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  void preflightForPatchRequestIsAccepted() throws IOException {
    MockHttpServletRequest request = crossOriginRequest("OPTIONS");
    request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH");

    MockHttpServletResponse response = new MockHttpServletResponse();
    boolean accepted = process(request, response);

    assertThat(accepted).as("preflight for PATCH must pass the CORS check").isTrue();
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("PATCH");
  }

  @Test
  void requestFromAForeignOriginStaysRejected() throws IOException {
    MockHttpServletRequest request = crossOriginRequest("PATCH");
    request.removeHeader(HttpHeaders.ORIGIN);
    request.addHeader(HttpHeaders.ORIGIN, "https://evil.example.org");

    MockHttpServletResponse response = new MockHttpServletResponse();
    boolean accepted = process(request, response);

    assertThat(accepted).as("foreign origins must still be rejected").isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  private MockHttpServletRequest crossOriginRequest(String method) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/chats/abc");
    // The backend behind the inner proxy sees plain http, the browser sends the https origin -
    // exactly the constellation of the test installation that makes the request cross-origin.
    request.setScheme("http");
    request.setServerName("opaa.example.org");
    request.setServerPort(80);
    request.addHeader(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
    return request;
  }

  private boolean process(MockHttpServletRequest request, MockHttpServletResponse response)
      throws IOException {
    CorsConfiguration config = source.getCorsConfiguration(request);
    assertThat(config).as("CORS configuration must cover /api/**").isNotNull();
    return new DefaultCorsProcessor().processRequest(config, request, response);
  }
}
