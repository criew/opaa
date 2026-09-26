package io.opaa.diagnosticaccess;

import io.opaa.auth.local.LocalAccountDeletionEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Revokes the impersonation grants a deleted local account issued, in the deletion's transaction -
 * see {@link DiagnosticImpersonationGrantService#revokeGrantsIssuedBy}.
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
}
