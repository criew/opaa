package io.opaa.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.common.McpTransportContext;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.CurrentUserArgumentResolver;
import io.opaa.auth.User;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Every MCP call is a token call: a caller without its token never reaches a tool. */
class McpRequestContextTest {

  @Test
  void aCallerThatArrivedOnATokenReachesTheTools() {
    UUID tokenId = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    CurrentUserArgumentResolver.bindExternalAccessCaller(
        request, new User("s", "i", null, "Person"), tokenId);
    CurrentUser caller = CurrentUserArgumentResolver.callerOf(request);

    CurrentUser resolved =
        McpRequestContext.callerOf(
            McpTransportContext.create(Map.<String, Object>of(McpRequestContext.CALLER, caller)));

    assertThat(resolved.accessTokenId()).isEqualTo(tokenId);
  }

  @Test
  void aCallerWithoutATokenIsRefusedRatherThanServedThePersonsView() {
    CurrentUser person =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "Person");
    McpTransportContext context =
        McpTransportContext.create(Map.<String, Object>of(McpRequestContext.CALLER, person));

    assertThatThrownBy(() -> McpRequestContext.callerOf(context))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aRequestWithoutAnyCallerIsRefused() {
    assertThatThrownBy(
            () -> McpRequestContext.callerOf(McpTransportContext.create(Map.<String, Object>of())))
        .isInstanceOf(IllegalStateException.class);
  }
}
