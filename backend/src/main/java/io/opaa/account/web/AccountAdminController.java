package io.opaa.account.web;

import io.opaa.account.AccountAdminService;
import io.opaa.account.AccountErasureService;
import io.opaa.account.AccountQuery;
import io.opaa.api.dto.AccountPageResponse;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.ProviderType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AuditorRoles;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.RequiresRecentLogin;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The account list of the administration: every account of the caller's organization, local and
 * identity-provider accounts alike, {@code SYSTEM_ADMIN} only. Read-only - the writes live in
 * {@code LocalUserAdminController} (local accounts) and {@code AdminController} (roles).
 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AccountAdminController {

  private final AccountAdminService adminService;
  private final AccountErasureService erasureService;
  private final AuditorRoles auditorRoles;

  public AccountAdminController(
      AccountAdminService adminService,
      AccountErasureService erasureService,
      AuditorRoles auditorRoles) {
    this.adminService = adminService;
    this.erasureService = erasureService;
    this.auditorRoles = auditorRoles;
  }

  // Irreversible: a left-open or stolen session must not erase a person's data.
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @RequiresRecentLogin
  @PostMapping("/{id}/erasure")
  public ResponseEntity<Void> eraseAccount(@PathVariable UUID id, @Caller CurrentUser caller) {
    erasureService.erase(caller, id);
    return ResponseEntity.noContent().build();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public AccountPageResponse listAccounts(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) ProviderType providerType,
      @RequestParam(required = false) UUID providerId,
      @RequestParam(required = false) SystemRole role,
      @RequestParam(required = false) LocalAccountState status,
      @RequestParam(defaultValue = "false") boolean withoutExpiry,
      @RequestParam(defaultValue = "false") boolean inactive,
      @RequestParam(defaultValue = "displayName") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + AccountQuery.DEFAULT_PAGE_SIZE) int size,
      @Caller CurrentUser caller) {
    AccountQuery accountQuery =
        new AccountQuery(
            query,
            providerType,
            providerId,
            role,
            status,
            withoutExpiry,
            inactive,
            AdminListSortParams.accountSortOf(sort),
            AdminListSortParams.descending(direction),
            page,
            size);
    return PendingRoleMarks.markAccounts(
        AccountResponseMapper.toPage(adminService.list(caller.organizationId(), accountQuery)),
        auditorRoles);
  }
}
