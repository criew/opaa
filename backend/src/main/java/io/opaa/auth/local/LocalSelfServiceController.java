package io.opaa.auth.local;

import io.opaa.api.dto.LocalForgotPasswordRequest;
import io.opaa.api.dto.LocalRegisterRequest;
import io.opaa.api.dto.LocalSetPasswordRequest;
import io.opaa.api.dto.LocalVerifyEmailRequest;
import io.opaa.auth.local.LocalAuthRateLimiter.AddressScope;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The self-service endpoints of local accounts under {@code /api/v1/auth/local} (ADR-0033,
 * Entscheidungen 9 and 11): redeeming an invitation or reset link, asking for a reset link,
 * registering, confirming the address. A switched-off flow is refused as the very {@link
 * NoResourceFoundException} an unknown route raises - before the address is counted or touched - so
 * nothing about the installation is probed through it; an available flow spends the address's
 * budget ({@link LocalAuthRateLimiter}) before anything happens with the address. The two
 * address-taking bodies are bound without {@code @Valid} on purpose: their field rules are checked
 * behind the availability gate, so a well-formed body never yields anything but the 404 while the
 * flow is off. Exists in the {@code oidc} profile only.
 */
@RestController
@RequestMapping("/api/v1/auth/local")
@Profile("oidc")
public class LocalSelfServiceController {

  private static final String BASE_PATH = "api/v1/auth/local/";

  private final LocalSelfServiceService service;
  private final LocalAuthRateLimiter rateLimiter;

  public LocalSelfServiceController(
      LocalSelfServiceService service, LocalAuthRateLimiter rateLimiter) {
    this.service = service;
    this.rateLimiter = rateLimiter;
  }

  @PostMapping("/set-password")
  public ResponseEntity<Void> setPassword(@Valid @RequestBody LocalSetPasswordRequest request) {
    service.setPassword(request.getToken(), request.getNewPassword());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/forgot-password")
  public ResponseEntity<Void> forgotPassword(@RequestBody LocalForgotPasswordRequest request)
      throws NoResourceFoundException {
    if (!service.isPasswordResetAvailable()) {
      throw unknownRoute("forgot-password");
    }
    rateLimiter.requireAddressAllowance(AddressScope.FORGOT_PASSWORD, request.getEmail());
    service.requestPasswordReset(request.getEmail());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/register")
  public ResponseEntity<Void> register(@RequestBody LocalRegisterRequest request)
      throws NoResourceFoundException {
    if (!service.isSelfRegistrationAvailable()) {
      throw unknownRoute("register");
    }
    rateLimiter.requireAddressAllowance(AddressScope.REGISTER, request.getEmail());
    service.register(request.getEmail(), request.getDisplayName(), request.getPassword());
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/verify-email")
  public ResponseEntity<Void> verifyEmail(@Valid @RequestBody LocalVerifyEmailRequest request) {
    service.verifyEmail(request.getToken());
    return ResponseEntity.noContent().build();
  }

  /** What Spring raises for a path nobody serves - the handler renders it as the standard 404. */
  private static NoResourceFoundException unknownRoute(String path) {
    return new NoResourceFoundException(HttpMethod.POST, BASE_PATH + path, BASE_PATH + path);
  }
}
