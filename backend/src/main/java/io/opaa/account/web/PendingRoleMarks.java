package io.opaa.account.web;

import io.opaa.api.dto.AccountPageResponse;
import io.opaa.api.dto.AccountResponse;
import io.opaa.api.dto.LocalUserResponse;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AuditorRoles;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Marks the accounts with an open AUDITOR request as {@code pendingSystemRole}, so that the
 * administration sees a requested role as requested rather than as granted or forgotten.
 */
final class PendingRoleMarks {

  private PendingRoleMarks() {}

  static LocalUserResponse mark(LocalUserResponse response, AuditorRoles auditorRoles) {
    markAll(List.of(response), auditorRoles);
    return response;
  }

  static void markAll(List<LocalUserResponse> responses, AuditorRoles auditorRoles) {
    Set<UUID> pending =
        auditorRoles.withOpenRequest(responses.stream().map(LocalUserResponse::getId).toList());
    for (LocalUserResponse response : responses) {
      if (pending.contains(response.getId())) {
        response.setPendingSystemRole(SystemRole.AUDITOR);
      }
    }
  }

  static AccountPageResponse markAccounts(AccountPageResponse page, AuditorRoles auditorRoles) {
    Set<UUID> pending =
        auditorRoles.withOpenRequest(page.getItems().stream().map(AccountResponse::getId).toList());
    for (AccountResponse account : page.getItems()) {
      if (pending.contains(account.getId())) {
        account.setPendingSystemRole(SystemRole.AUDITOR);
        if (account.getLocal() != null) {
          account.getLocal().setPendingSystemRole(SystemRole.AUDITOR);
        }
      }
    }
    return page;
  }
}
