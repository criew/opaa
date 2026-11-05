package io.opaa.diagnosticaccess;

import io.opaa.account.AccountErasureEvent;
import io.opaa.account.LocalAccountDeletionEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Revokes the impersonation grants a deleted or erased account issued, in the transaction of the
 * deletion or erasure - see {@link DiagnosticImpersonationGrantService#revokeGrantsIssuedBy}.
 */
@Component
class DiagnosticImpersonationGrantAccountDeletionListener {

  private final DiagnosticImpersonationGrantService grants;

  DiagnosticImpersonationGrantAccountDeletionListener(DiagnosticImpersonationGrantService grants) {
    this.grants = grants;
  }

  @Order(LocalAccountDeletionEvent.IMPERSONATION_GRANTS_ORDER)
  @EventListener
  void onAccountDeletion(LocalAccountDeletionEvent event) {
    grants.revokeGrantsIssuedBy(event.actor(), event.user().getId());
  }

  @Order(AccountErasureEvent.IMPERSONATION_GRANTS_ORDER)
  @EventListener
  void onAccountErasure(AccountErasureEvent event) {
    grants.revokeGrantsIssuedBy(event.actor(), event.user().getId());
  }
}
