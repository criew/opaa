package io.opaa.auth;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opaa.auth.auditor-role.*}: the one way the operation names the first auditor of an
 * organization without an identity provider's roles claim. While the organization has no AUDITOR,
 * nobody can confirm a request, so the system administration's grant takes effect only for the
 * account with exactly this address; every later grant needs an auditor's confirmation.
 *
 * @param firstAuditorEmail {@code OPAA_AUTH_FIRST_AUDITOR_EMAIL}: the address of the account that
 *     may become the first auditor; trimmed, empty means none
 */
@InstallationWide
@NoDataImpact(
    "names the account that may become the first auditor; the grant itself is a recorded role"
        + " change")
@ConfigurationProperties(prefix = "opaa.auth.auditor-role")
public record AuditorRoleProperties(String firstAuditorEmail) {

  public static final String FIRST_AUDITOR_EMAIL_VARIABLE = "OPAA_AUTH_FIRST_AUDITOR_EMAIL";

  public AuditorRoleProperties {
    firstAuditorEmail = firstAuditorEmail == null ? "" : firstAuditorEmail.trim();
  }

  /** Whether {@code email} is the configured address, ignoring case; never for an empty one. */
  public boolean namesFirstAuditor(String email) {
    return !firstAuditorEmail.isEmpty()
        && email != null
        && firstAuditorEmail.toLowerCase(Locale.ROOT).equals(email.trim().toLowerCase(Locale.ROOT));
  }
}
