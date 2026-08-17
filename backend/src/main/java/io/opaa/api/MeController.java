package io.opaa.api;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.group.GroupService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Self-service endpoints scoped to the caller, deliberately separate from {@link
 * io.opaa.auth.UserInfoController} (which owns {@code /api/v1/auth/me}, the identity endpoint used
 * during login) and from {@link GroupController} (whose {@code /api/v1/admin/groups} is
 * system-admin only). {@link #myGroups} exists because the library-creation dialog needs the
 * caller's own group memberships to offer a GROUP owner - {@code GroupService#listMyGroups}'s
 * Javadoc explains why {@code listGroups} cannot serve that purpose.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final GroupService groupService;
  private final UserService userService;

  public MeController(GroupService groupService, UserService userService) {
    this.groupService = groupService;
    this.userService = userService;
  }

  @GetMapping("/groups")
  public List<GroupListResponse> myGroups(@AuthenticationPrincipal Jwt jwt) {
    return groupService.listMyGroups(currentUser(jwt).getId());
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
