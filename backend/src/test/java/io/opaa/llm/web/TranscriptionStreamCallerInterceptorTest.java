package io.opaa.llm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.CurrentUserArgumentResolver;
import io.opaa.auth.User;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

/**
 * The upgrade takes the caller the security chain resolved and refuses one that came through the
 * access token of an external AI tool, even if a chain in front ever let such a token pass.
 */
class TranscriptionStreamCallerInterceptorTest {

  private final TranscriptionStreamConfiguration.CallerInterceptor interceptor =
      new TranscriptionStreamConfiguration.CallerInterceptor();
  private final MockHttpServletRequest request =
      new MockHttpServletRequest("GET", TranscriptionStreamConfiguration.PATH);
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final Map<String, Object> attributes = new HashMap<>();

  private boolean handshake() {
    return interceptor.beforeHandshake(
        new ServletServerHttpRequest(request),
        new ServletServerHttpResponse(response),
        mock(WebSocketHandler.class),
        attributes);
  }

  @Test
  void aCallerOfAnExternalAccessTokenIsRefused() {
    CurrentUserArgumentResolver.bindExternalAccessCaller(
        request, new User("subject", "issuer", "erika@stadt.example", "Erika"), UUID.randomUUID());

    assertThat(handshake()).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(attributes).isEmpty();
  }

  @Test
  void aSignedInPersonIsTakenAsTheCaller() {
    CurrentUser caller =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "Erika");
    request.setAttribute(CurrentUserArgumentResolver.class.getName() + ".value", caller);

    assertThat(handshake()).isTrue();
    assertThat(attributes).containsEntry(TranscriptionStreamHandler.CALLER_ATTRIBUTE, caller);
  }

  @Test
  void anAnonymousUpgradeIsRefused() {
    assertThat(handshake()).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
  }
}
