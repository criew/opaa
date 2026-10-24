package io.opaa.selfdisclosure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.DevSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The frame headers under the {@code dev} chain, every request as the default development user. */
@WebMvcTest(controllers = SelfDisclosureController.class)
@Import({
  DevSecurityConfig.class,
  SelfDisclosureDownloadFramingContract.CorsStub.class,
  SelfDisclosureDownloadFramingDevTest.DevUsers.class
})
@ActiveProfiles("dev")
class SelfDisclosureDownloadFramingDevTest extends SelfDisclosureDownloadFramingContract {

  @TestConfiguration
  static class DevUsers {
    @Bean
    AuthProperties authProperties() {
      return new AuthProperties(
          "dev",
          null,
          new AuthProperties.DevAuth(
              "opaa-dev",
              "dev-user",
              List.of(new AuthProperties.DevUser("dev-user", "dev@example.org", "Dev"))),
          null);
    }
  }

  @MockitoBean private UserService userService;

  @BeforeEach
  void developmentUser() {
    User user = mock(User.class);
    when(user.getSystemRole()).thenReturn(SystemRole.USER);
    when(userService.provisionFromToken(any())).thenReturn(user);
  }
}
