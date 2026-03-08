package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class UserInfoControllerTest {

  @Mock private UserService userService;

  @InjectMocks private UserInfoController userInfoController;

  @Test
  void meUsesStringIssuerClaimWithoutUrlConversion() {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .claim("sub", "admin")
            .claim("iss", "opaa-basic")
            .claim("email", "admin@opaa.local")
            .claim("name", "admin")
            .build();
    User user = new User("admin", "opaa-basic", "admin@opaa.local", "admin");

    when(userService.findOrCreateUser("admin", "opaa-basic", "admin@opaa.local", "admin"))
        .thenReturn(user);

    var response = userInfoController.me(jwt);

    verify(userService).findOrCreateUser("admin", "opaa-basic", "admin@opaa.local", "admin");
    assertThat(response.getEmail()).isEqualTo("admin@opaa.local");
    assertThat(response.getDisplayName()).isEqualTo("admin");
    assertThat(response.getId()).isEqualTo(user.getId());
  }

  @Test
  void meFallsBackToUnknownIssuerWhenClaimMissing() {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .claim("sub", "admin")
            .claim("email", "admin@opaa.local")
            .claim("name", "admin")
            .build();
    User user = new User("admin", "unknown", "admin@opaa.local", "admin");

    when(userService.findOrCreateUser("admin", "unknown", "admin@opaa.local", "admin"))
        .thenReturn(user);

    var response = userInfoController.me(jwt);

    verify(userService).findOrCreateUser("admin", "unknown", "admin@opaa.local", "admin");
    assertThat(response.getId()).isEqualTo(user.getId());
    assertThat(response.getEmail()).isEqualTo("admin@opaa.local");
    assertThat(response.getDisplayName()).isEqualTo("admin");
  }
}
