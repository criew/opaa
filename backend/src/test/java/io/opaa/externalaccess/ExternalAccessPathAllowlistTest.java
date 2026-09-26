package io.opaa.externalaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

class ExternalAccessPathAllowlistTest {

  private final RequestMatcher ownedMatcher = request -> false;
  private final ExternalAccessOwnedPath ownedPath = () -> ownedMatcher;

  // Spring injects an empty list rather than failing, so this refusal alone keeps /mcp owned.
  @Test
  void refusesToStartWithoutAnOwnedPath() {
    assertThatThrownBy(() -> new ExternalAccessPathAllowlist(List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void authorisesTheReadingPathsPlusTheContributedMatcher() {
    ExternalAccessPathAllowlist allowlist = new ExternalAccessPathAllowlist(List.of(ownedPath));

    List<RequestMatcher> matchers = allowlist.matchers();
    assertThat(matchers).hasSize(4);
    assertThat(matchers.get(0).matches(request("POST", "/api/v1/search"))).isTrue();
    assertThat(matchers.get(1).matches(request("GET", "/api/v1/search/libraries"))).isTrue();
    assertThat(matchers.get(2).matches(request("GET", "/api/v1/search/hits/abc"))).isTrue();
    assertThat(matchers.get(3)).isSameAs(ownedMatcher);
  }

  @Test
  void ownsOnlyTheContributedMatcher() {
    ExternalAccessPathAllowlist allowlist = new ExternalAccessPathAllowlist(List.of(ownedPath));

    assertThat(allowlist.ownedMatchers()).containsExactly(ownedMatcher);
  }

  private static MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }
}
