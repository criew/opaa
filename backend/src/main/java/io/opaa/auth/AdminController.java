package io.opaa.auth;

import io.opaa.api.dto.RoleChangeRequest;
import io.opaa.api.dto.UserInfoResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final UserService userService;

  public AdminController(UserService userService) {
    this.userService = userService;
  }

  // #271: scoped to the acting SYSTEM_ADMIN's own organization - listing every organization's
  // users used to be reachable here, the one place the organization boundary (#199) had not been
  // applied yet.
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/users")
  public List<UserInfoResponse> listUsers(@AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return userService.findAllInOrganization(currentUser.getOrganizationId()).stream()
        .map(this::toResponse)
        .toList();
  }

  // #392 code review, finding 3: the acting person is now resolved and passed through, the same
  // @AuthenticationPrincipal Jwt / currentUser(jwt) pattern LibraryController already uses - see
  // UserService#updateRole for why it needs one (SYSTEM_ADMIN_ROLE_GRANTED/_REVOKED). #271: the
  // full acting User (not just its id) is now passed through so UserService#updateRole can reject
  // a target user from another organization with 404, the same as every other foreign-user-id path
  // guarded by SpaceService#requireUserInOrganization.
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/users/{id}/role")
  public ResponseEntity<UserInfoResponse> changeRole(
      @PathVariable UUID id,
      @Valid @RequestBody RoleChangeRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    User user = userService.updateRole(id, request.getRole(), currentUser);
    return ResponseEntity.ok(toResponse(user));
  }

  @ExceptionHandler(UserNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Map<String, String> handleUserNotFound(UserNotFoundException ex) {
    return Map.of("error", ex.getMessage());
  }

  private UserInfoResponse toResponse(User user) {
    return new UserInfoResponse(
        user.getId(), user.getEmail(), user.getDisplayName(), user.getSystemRole().name());
  }

  private User currentUser(Jwt jwt) {
    String issuer = jwt.getClaimAsString("iss");
    if (issuer == null || issuer.isBlank()) {
      issuer = UNKNOWN_ISSUER;
    }
    return userService
        .findBySubjectAndIssuer(jwt.getSubject(), issuer)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));
  }
}
