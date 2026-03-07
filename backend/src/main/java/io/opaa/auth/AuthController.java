package io.opaa.auth;

import io.opaa.auth.dto.LoginRequest;
import io.opaa.auth.dto.LoginResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("basic")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthProperties authProperties;
  private final JwtTokenService jwtTokenService;
  private final UserService userService;

  public AuthController(
      AuthProperties authProperties, JwtTokenService jwtTokenService, UserService userService) {
    this.authProperties = authProperties;
    this.jwtTokenService = jwtTokenService;
    this.userService = userService;
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
    AuthProperties.BasicAuth basic = authProperties.basic();
    if (!basic.username().equals(request.username())
        || !basic.password().equals(request.password())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "Invalid credentials"));
    }

    String token =
        jwtTokenService.generateToken(
            request.username(), request.username() + "@opaa.local", request.username());

    userService.findOrCreateUser(
        request.username(),
        jwtTokenService.getIssuer(),
        request.username() + "@opaa.local",
        request.username());

    return ResponseEntity.ok(new LoginResponse(token, jwtTokenService.getExpirationSeconds()));
  }
}
