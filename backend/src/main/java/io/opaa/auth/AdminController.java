package io.opaa.auth;

import io.opaa.auth.dto.RoleChangeRequest;
import io.opaa.auth.dto.UserInfoResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile({"oidc", "basic"})
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

  private final UserService userService;

  public AdminController(UserService userService) {
    this.userService = userService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/users")
  public List<UserInfoResponse> listUsers() {
    return userService.findAll().stream().map(this::toResponse).toList();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/users/{id}/role")
  public ResponseEntity<UserInfoResponse> changeRole(
      @PathVariable UUID id, @Valid @RequestBody RoleChangeRequest request) {
    User user = userService.updateRole(id, request.role());
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
}
