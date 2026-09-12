package io.opaa.api;

import io.opaa.api.dto.LocalUserCreateRequest;
import io.opaa.api.dto.LocalUserCreatedResponse;
import io.opaa.api.dto.LocalUserCreationMode;
import io.opaa.api.dto.LocalUserGeneratedPasswordResponse;
import io.opaa.api.dto.LocalUserLockRequest;
import io.opaa.api.dto.LocalUserPageResponse;
import io.opaa.api.dto.LocalUserPasswordResetResponse;
import io.opaa.api.dto.LocalUserResponse;
import io.opaa.api.dto.LocalUserSummaryResponse;
import io.opaa.api.dto.LocalUserUpdateRequest;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.local.LocalUserAdminService;
import io.opaa.auth.local.LocalUserCreation;
import io.opaa.auth.local.LocalUserQuery;
import io.opaa.auth.local.LocalUserUpdate;
import io.opaa.common.ValidationException;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administration of local accounts (ADR-0033, Entscheidung 11), {@code SYSTEM_ADMIN} only and
 * scoped to the caller's organization: the list with its review filters, creation by invitation or
 * generated password, change, lock and unlock, reset by link or generated password, deletion. The
 * secrets a response carries once - the link when it was not mailed, a generated password - come
 * from the domain and are never read back.
 */
@RestController
@RequestMapping("/api/v1/admin/local-users")
public class LocalUserAdminController {

  private final LocalUserAdminService adminService;

  public LocalUserAdminController(LocalUserAdminService adminService) {
    this.adminService = adminService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public LocalUserPageResponse listLocalUsers(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) LocalAccountState status,
      @RequestParam(required = false) SystemRole role,
      @RequestParam(defaultValue = "false") boolean withoutExpiry,
      @RequestParam(defaultValue = "false") boolean inactive,
      @RequestParam(defaultValue = "displayName") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + LocalUserQuery.DEFAULT_PAGE_SIZE) int size,
      @Caller CurrentUser caller) {
    LocalUserQuery localUserQuery =
        new LocalUserQuery(
            query,
            status,
            role,
            withoutExpiry,
            inactive,
            sortOf(sort),
            descending(direction),
            page,
            size);
    return LocalUserResponseMapper.toPage(
        adminService.list(caller.organizationId(), localUserQuery));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/summary")
  public LocalUserSummaryResponse getLocalUserSummary(@Caller CurrentUser caller) {
    return LocalUserResponseMapper.toSummary(adminService.summary(caller.organizationId()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping
  public ResponseEntity<LocalUserCreatedResponse> createLocalUser(
      @Valid @RequestBody LocalUserCreateRequest request, @Caller CurrentUser caller) {
    LocalUserCreationMode mode = request.getMode();
    LocalUserCreation creation =
        new LocalUserCreation(
            request.getEmail(),
            request.getDisplayName(),
            request.getSystemRole(),
            request.getExpiresAt(),
            Boolean.TRUE.equals(request.getNoExpiry()),
            request.getCreatedReason(),
            mode == LocalUserCreationMode.INVITE);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(LocalUserResponseMapper.toCreated(adminService.create(caller, creation), mode));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/{id}")
  public LocalUserResponse getLocalUser(@PathVariable UUID id, @Caller CurrentUser caller) {
    return LocalUserResponseMapper.toResponse(adminService.get(caller.organizationId(), id));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PatchMapping("/{id}")
  public LocalUserResponse updateLocalUser(
      @PathVariable UUID id,
      @Valid @RequestBody LocalUserUpdateRequest request,
      @Caller CurrentUser caller) {
    LocalUserUpdate update =
        new LocalUserUpdate(
            request.getEmail(),
            request.getDisplayName(),
            request.getSystemRole(),
            request.getExpiresAt(),
            Boolean.TRUE.equals(request.getNoExpiry()),
            request.getCreatedReason());
    return LocalUserResponseMapper.toResponse(adminService.update(caller, id, update));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteLocalUser(@PathVariable UUID id, @Caller CurrentUser caller) {
    adminService.delete(caller, id);
    return ResponseEntity.noContent().build();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{id}/lock")
  public LocalUserResponse lockLocalUser(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) LocalUserLockRequest request,
      @Caller CurrentUser caller) {
    return LocalUserResponseMapper.toResponse(
        adminService.lock(caller, id, request == null ? null : request.getReason()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{id}/unlock")
  public LocalUserResponse unlockLocalUser(@PathVariable UUID id, @Caller CurrentUser caller) {
    return LocalUserResponseMapper.toResponse(adminService.unlock(caller, id));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{id}/password-reset")
  public LocalUserPasswordResetResponse requestLocalUserPasswordReset(
      @PathVariable UUID id, @Caller CurrentUser caller) {
    return LocalUserResponseMapper.toPasswordReset(adminService.requestPasswordReset(caller, id));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{id}/password")
  public LocalUserGeneratedPasswordResponse generateLocalUserPassword(
      @PathVariable UUID id, @Caller CurrentUser caller) {
    return new LocalUserGeneratedPasswordResponse(adminService.generatePassword(caller, id));
  }

  /** The allow-list of sort fields; the activity is deliberately not one of them. */
  private static LocalUserQuery.Sort sortOf(String sort) {
    return switch (sort == null ? "" : sort.trim()) {
      case "displayName", "" -> LocalUserQuery.Sort.DISPLAY_NAME;
      case "email" -> LocalUserQuery.Sort.EMAIL;
      case "expiresAt" -> LocalUserQuery.Sort.EXPIRES_AT;
      case "createdAt" -> LocalUserQuery.Sort.CREATED_AT;
      default ->
          throw new ValidationException(
              "Sortierung nur nach displayName, email, expiresAt oder createdAt.");
    };
  }

  private static boolean descending(String direction) {
    return switch (direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT)) {
      case "asc", "" -> false;
      case "desc" -> true;
      default -> throw new ValidationException("Sortierrichtung nur asc oder desc.");
    };
  }
}
