package io.opaa.auth;

import io.opaa.api.dto.ErrorResponse;
import io.opaa.auth.dto.LoginRequest;
import io.opaa.auth.dto.LoginResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
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
  private final PasswordEncoder passwordEncoder;

  public AuthController(
      AuthProperties authProperties,
      JwtTokenService jwtTokenService,
      UserService userService,
      PasswordEncoder passwordEncoder) {
    this.authProperties = authProperties;
    this.jwtTokenService = jwtTokenService;
    this.userService = userService;
    this.passwordEncoder = passwordEncoder;
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
    AuthProperties.BasicAuth basic = authProperties.basic();
    if (!isValidCredentials(basic, request)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(
              new ErrorResponse(
                  "Invalid credentials", HttpStatus.UNAUTHORIZED.value(), Instant.now()));
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

  private boolean isValidCredentials(AuthProperties.BasicAuth basic, LoginRequest request) {
    if (basic == null
        || basic.username() == null
        || basic.password() == null
        || !basic.username().equals(request.username())) {
      return false;
    }

    if (basic.password().startsWith("{")) {
      return passwordEncoder.matches(request.password(), basic.password());
    }

    return basic.password().equals(request.password());
  }
}
