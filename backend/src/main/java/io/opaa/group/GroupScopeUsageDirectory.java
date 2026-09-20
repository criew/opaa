package io.opaa.group;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Which groups are the scope of an authorisation that lives outside the permission model - today
 * the diagnostic impersonation grants of ADR-0016. Declared here because this package asks the
 * question and implemented by {@code io.opaa.diagnosticaccess}, which owns the answer; the
 * dependency direction stays {@code diagnosticaccess} &rarr; {@code group}.
 *
 * <p>{@code fk_diagnostic_impersonation_grants_scope_organization} is {@code ON DELETE CASCADE}
 * (changeset 003): a grant disappears with its scope group, silently and without the revocation
 * event ADR-0016 requires. A group carrying one is therefore not "without effect".
 */
public interface GroupScopeUsageDirectory {

  /**
   * One entry per authorisation that still confers something and names one of {@code groupIds} as
   * its scope - so the caller learns both which groups are affected and how many authorisations are
   * at stake. A revoked authorisation already carries its revocation event and one whose window has
   * run out confers nothing, so neither counts.
   */
  List<UUID> scopeGroupsOfUnspentAuthorizations(Collection<UUID> groupIds);
}
