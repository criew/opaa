package io.opaa.auth.oidc;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Which token claims a provider's users are read from (ADR-0025, Entscheidung 4). Stored on the
 * provider row (#1329) and evaluated by the provisioning path (#1331). {@code null} for {@code
 * rolesClaim}/{@code groupsClaim} means "this provider derives no roles / no groups from its
 * tokens"; the two role values name the token role that grants the respective system role.
 *
 * @param emailClaim source of {@code users.email}; {@code email} for every OIDC-conformant provider
 * @param displayNameClaim source of {@code users.display_name}; the provisioning path falls back to
 *     {@code preferred_username}, never to the subject (a raw identifier is no display name)
 * @param rolesClaim dot-separated path to the roles claim, e.g. {@code realm_access.roles}
 * @param groupsClaim dot-separated path to the groups claim, e.g. {@code groups}
 */
@Embeddable
public record OidcClaimMapping(
    @Column(name = "email_claim", nullable = false, length = 100) String emailClaim,
    @Column(name = "display_name_claim", nullable = false, length = 100) String displayNameClaim,
    @Column(name = "roles_claim", length = 200) String rolesClaim,
    @Column(name = "system_admin_role", length = 255) String systemAdminRole,
    @Column(name = "auditor_role", length = 255) String auditorRole,
    @Column(name = "groups_claim", length = 200) String groupsClaim) {

  public static final String DEFAULT_EMAIL_CLAIM = "email";
  public static final String DEFAULT_DISPLAY_NAME_CLAIM = "name";

  /** Blank optional values become {@code null}; blank mandatory values take their default. */
  public OidcClaimMapping {
    emailClaim = orDefault(emailClaim, DEFAULT_EMAIL_CLAIM);
    displayNameClaim = orDefault(displayNameClaim, DEFAULT_DISPLAY_NAME_CLAIM);
    rolesClaim = blankToNull(rolesClaim);
    systemAdminRole = blankToNull(systemAdminRole);
    auditorRole = blankToNull(auditorRole);
    groupsClaim = blankToNull(groupsClaim);
  }

  /** The mapping today's single Keycloak issuer effectively had: email and name, no roles. */
  public static OidcClaimMapping keycloakDefaults() {
    return new OidcClaimMapping(
        DEFAULT_EMAIL_CLAIM, DEFAULT_DISPLAY_NAME_CLAIM, null, null, null, null);
  }

  private static String orDefault(String value, String fallback) {
    String trimmed = blankToNull(value);
    return trimmed == null ? fallback : trimmed;
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
