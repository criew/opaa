package io.opaa.auth;

import io.opaa.api.dto.UserSummaryResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deliberately separate from {@link AdminController} (whose {@code GET /api/v1/admin/users}
 * requires {@code SYSTEM_ADMIN}, unchanged): the member/grant pickers on {@code
 * SpaceManagementPage}, {@code SpaceCreatePage}, {@code LibraryCreatePage} and {@code
 * LibraryGrantsDialog} need to search for a user to add, and every caller reaching those pages is
 * an ordinary space/library member, not necessarily a system admin (#777). Before this endpoint,
 * those pickers called the admin-only list, got a silently-swallowed 403 for anyone without {@code
 * SYSTEM_ADMIN}, and rendered a permanently empty, dead-looking search field - "Mitglied
 * hinzufügen" was unusable for every non-admin. Same precedent as {@code MeController#myGroups}
 * (any authenticated user needs their own group memberships) and {@code
 * AssetGrantService#toResponses}'s server-side name resolution (#423 code review): the threshold
 * this endpoint needs is "authenticated member of the organization", not {@code SYSTEM_ADMIN}.
 *
 * <p>Returns {@link UserSummaryResponse} (id/email/displayName only, no {@code systemRole}) -
 * deliberately narrower than {@link io.opaa.api.dto.UserInfoResponse}, the admin list's response
 * type: this endpoint's audience is every authenticated organization member, not just SYSTEM_ADMIN,
 * so it exposes less, the same reasoning {@code KnowledgeLibraryService#resolveOwnerNames} applies
 * to an organization-wide library-owner list.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserSearchController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final UserService userService;

  public UserSearchController(UserService userService) {
    this.userService = userService;
  }

  // #778 review, finding 4: query is required in practice - see UserService#searchInOrganization,
  // which returns an empty list rather than the whole organization for a missing or too-short one.
  @GetMapping
  public List<UserSummaryResponse> listUsers(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String query) {
    User currentUser = currentUser(jwt);
    return userService.searchInOrganization(currentUser.getOrganizationId(), query).stream()
        .map(this::toResponse)
        .toList();
  }

  private UserSummaryResponse toResponse(User user) {
    return new UserSummaryResponse(user.getId(), user.getEmail(), user.getDisplayName());
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
