package io.opaa.auth;

import io.opaa.auth.dto.UserInfoResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile({"oidc", "basic"})
@RestController
@RequestMapping("/api/v1/auth")
public class UserInfoController {

  private final UserService userService;

  public UserInfoController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping("/me")
  public UserInfoResponse me(@AuthenticationPrincipal Jwt jwt) {
    String subject = jwt.getSubject();
    String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : "unknown";
    User user =
        userService.findOrCreateUser(
            subject, issuer, jwt.getClaimAsString("email"), jwt.getClaimAsString("name"));
    return new UserInfoResponse(user.getId(), user.getEmail(), user.getDisplayName());
  }
}
