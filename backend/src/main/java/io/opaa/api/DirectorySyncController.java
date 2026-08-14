package io.opaa.api;

import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.api.dto.DirectorySyncStatusResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.group.sync.DirectorySyncService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/directory-sync")
public class DirectorySyncController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final DirectorySyncService directorySyncService;
  private final UserService userService;

  public DirectorySyncController(
      DirectorySyncService directorySyncService, UserService userService) {
    this.directorySyncService = directorySyncService;
    this.userService = userService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/dry-run")
  public DirectorySyncReportResponse dryRun(@AuthenticationPrincipal Jwt jwt) {
    return directorySyncService.dryRun(currentUser(jwt).getOrganizationId());
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/run")
  public DirectorySyncReportResponse run(@AuthenticationPrincipal Jwt jwt) {
    return directorySyncService.run(currentUser(jwt).getOrganizationId());
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/status")
  public DirectorySyncStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
    return directorySyncService.getStatus(currentUser(jwt).getOrganizationId());
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
