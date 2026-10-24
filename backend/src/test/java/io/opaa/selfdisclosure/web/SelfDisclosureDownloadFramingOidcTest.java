package io.opaa.selfdisclosure.web;

import io.opaa.account.OidcSecurityConfig;
import io.opaa.auth.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The frame headers under the {@code oidc} chain; requests without a token are anonymous. */
@WebMvcTest(controllers = SelfDisclosureController.class)
@Import({OidcSecurityConfig.class, SelfDisclosureDownloadFramingContract.CorsStub.class})
@ActiveProfiles("oidc")
class SelfDisclosureDownloadFramingOidcTest extends SelfDisclosureDownloadFramingContract {

  @MockitoBean private UserService userService;

  @MockitoBean
  private AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver;
}
