package io.opaa.api;

import io.opaa.api.dto.AccountPageResponse;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.ProviderType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.account.AccountAdminService;
import io.opaa.auth.account.AccountQuery;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The account list of the administration (#1601): every account of the caller's organization, local
 * and identity-provider accounts alike, {@code SYSTEM_ADMIN} only. Read-only - the writes live in
 * {@code LocalUserAdminController} (local accounts) and {@code AdminController} (roles).
 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AccountAdminController {

  private final AccountAdminService adminService;

  public AccountAdminController(AccountAdminService adminService) {
    this.adminService = adminService;
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
            AdminListSortParams.sortOf(sort),
            AdminListSortParams.descending(direction),
            page,
            size);
    return AccountResponseMapper.toPage(adminService.list(caller.organizationId(), accountQuery));
  }
}
