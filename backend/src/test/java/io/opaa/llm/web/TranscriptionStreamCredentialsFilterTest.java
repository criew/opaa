package io.opaa.llm.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.DevAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The credentials of a browser WebSocket reach the security chain as the headers a REST call
 * carries, and the subprotocol the handshake answers is never one that holds a credential.
 */
class TranscriptionStreamCredentialsFilterTest {

  private final TranscriptionStreamCredentialsFilter filter =
      new TranscriptionStreamCredentialsFilter();

  private HttpServletRequest filtered(MockHttpServletRequest request) throws Exception {
    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest next, jakarta.servlet.ServletResponse response) {
            seen.set((HttpServletRequest) next);
          }
        });
    return seen.get();
  }

  @Test
  void theTokenBecomesTheAuthorizationHeaderAndLeavesTheSubprotocols() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/transcriptions/stream");
    request.addHeader(
        "Sec-WebSocket-Protocol", "opaa-transcription.v1, opaa-bearer.eyJhbGciOi.payload.sig");

    HttpServletRequest seen = filtered(request);

    assertThat(seen.getHeader("Authorization")).isEqualTo("Bearer eyJhbGciOi.payload.sig");
    assertThat(seen.getHeader("Sec-WebSocket-Protocol")).isEqualTo("opaa-transcription.v1");
    assertThat(Collections.list(seen.getHeaders("sec-websocket-protocol")))
        .containsExactly("opaa-transcription.v1");
  }

  @Test
  void theDevUserBecomesTheDevUserHeader() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/transcriptions/stream");
    request.addHeader("Sec-WebSocket-Protocol", "opaa-transcription.v1");
    request.addHeader("Sec-WebSocket-Protocol", "opaa-dev-user.dev-admin");

    HttpServletRequest seen = filtered(request);

    assertThat(seen.getHeader(DevAuthFilter.DEV_USER_HEADER)).isEqualTo("dev-admin");
    assertThat(Collections.list(seen.getHeaderNames()))
        .anyMatch(name -> name.equalsIgnoreCase(DevAuthFilter.DEV_USER_HEADER));
  }

  @Test
  void aHeaderTheRequestAlreadyCarriesIsNeverReplaced() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/transcriptions/stream");
    request.addHeader("Authorization", "Bearer from-header");
    request.addHeader("Sec-WebSocket-Protocol", "opaa-transcription.v1, opaa-bearer.from-protocol");

    assertThat(filtered(request).getHeader("Authorization")).isEqualTo("Bearer from-header");
  }

  @Test
  void aRequestWithoutSubprotocolsPassesUnchanged() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/transcriptions/stream");

    assertThat(filtered(request)).isSameAs(request);
  }
}
