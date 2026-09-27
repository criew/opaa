package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the baseline's identity changeSet: accounts, identity providers, local accounts
 * with their tokens and settings (ADR-0033), mail, branding, notifications and the object types of
 * the audit trail. The audit trail's privilege model has its own class, {@link
 * AuditPrivilegeModelTest}.
 */
class IdentityBaselineTest extends AbstractBaselineTest {

  private static final String LOCAL_ISSUER = "urn:opaa:local";

  // ---------------------------------------------------------------------------------------------
  // Accounts and identity providers
  // ---------------------------------------------------------------------------------------------

  /**
   * The e-mail address is the sign-in name of a local account: unique case-insensitively, but only
   * among local accounts - an OIDC account may carry the same address (ADR-0025).
   */
  @Test
  void aLocalEmailIsUniqueCaseInsensitivelyAmongLocalAccountsOnly() throws SQLException {
    insertUser(LOCAL_ISSUER, "Anna.Berg@example.org");

    assertRejected(insertUserSql(LOCAL_ISSUER, "anna.berg@EXAMPLE.org"), "ux_users_local_email");
    insertUser("https://idp.example/realms/a", "anna.berg@example.org");
    insertUser("https://idp.example/realms/b", "anna.berg@example.org");
  }

  @Test
  void rejectsASecondOidcProviderWhoseIssuerDiffersOnlyInTrailingSlashes() throws SQLException {
    insertProvider("https://idp.example/realms/a/", true);

    assertRejected(
        providerSql("https://idp.example/realms/a", "OIDC", "'opaa-frontend'", false),
        "ux_oidc_providers_issuer_uri_normalized");
    assertRejected(
        providerSql("https://idp.example/realms/a//", "OIDC", "'opaa-frontend'", false),
        "ux_oidc_providers_issuer_uri_normalized");
    // Stored byte for byte: a token's "iss" claim is compared against it unchanged.
    assertThat(stringOf("SELECT issuer_uri FROM oidc_providers"))
        .isEqualTo("https://idp.example/realms/a/");
  }

  @Test
  void allowsAtMostOneDefaultOidcProviderAndSeedsItsMarkerAtMostOnce() throws SQLException {
    insertProvider("https://idp.example/realms/a", true);
    insertProvider("https://idp.example/realms/b", false);

    assertRejected(
        providerSql("https://idp.example/realms/c", "OIDC", "'opaa-frontend'", true),
        "ux_oidc_providers_single_default");

    assertThat(countRows("oidc_provider_seed_marker")).isZero();
    execute("INSERT INTO oidc_provider_seed_marker (id, seeded_at) VALUES (1, now())");
    assertRejected(
        "INSERT INTO oidc_provider_seed_marker (id, seeded_at) VALUES (2, now())",
        "chk_oidc_provider_seed_marker_singleton");
  }

  /** ADR-0033, Entscheidung 4: a provider row is OIDC unless declared LOCAL. */
  @Test
  void aProviderIsOidcByDefaultAndOnlyAnOidcRowCarriesAClientId() throws SQLException {
    UUID provider = insertProvider();

    assertThat(stringOf("SELECT provider_type FROM oidc_providers WHERE id = '" + provider + "'"))
        .isEqualTo("OIDC");
    assertRejected(
        providerSql("https://idp.example/realms/x", "SAML", "NULL", false),
        "chk_oidc_providers_provider_type");
    assertRejected(
        providerSql("https://idp.example/realms/y", "OIDC", "NULL", false),
        "chk_oidc_providers_client_id_by_type");
    assertRejected(
        providerSql(LOCAL_ISSUER, "LOCAL", "'opaa-frontend'", false),
        "chk_oidc_providers_client_id_by_type");
  }

  /**
   * The row of the local accounts exists at most once, is never the default (directory) provider,
   * keeps its fixed issuer, and is never external.
   */
  @Test
  void theLocalProviderRowIsUniqueNeverDefaultPinnedToItsIssuerAndNeverExternal()
      throws SQLException {
    assertRejected(
        providerSql(LOCAL_ISSUER, "LOCAL", "NULL", true), "chk_oidc_providers_local_row");
    assertRejected(
        providerSql("urn:opaa:other", "LOCAL", "NULL", false), "chk_oidc_providers_local_row");

    execute(providerSql(LOCAL_ISSUER, "LOCAL", "NULL", false));
    assertThat(booleanOf("SELECT is_external FROM oidc_providers WHERE provider_type = 'LOCAL'"))
        .isFalse();
    assertRejected(
        "UPDATE oidc_providers SET is_external = true WHERE provider_type = 'LOCAL'",
        "chk_oidc_providers_local_not_external");
    // A second LOCAL row collides on the single-LOCAL index or the normalized issuer - both unique.
    assertRejected(providerSql(LOCAL_ISSUER, "LOCAL", "NULL", false), "ux_oidc_providers");
  }

  /** A provider somebody inserts without saying is taken to be the foreign one - the safe side. */
  @Test
  void aProviderWrittenWithoutTheExternalMarkIsExternal() throws SQLException {
    UUID provider = insertProvider();

    assertThat(booleanOf("SELECT is_external FROM oidc_providers WHERE id = '" + provider + "'"))
        .isTrue();
  }

  /**
   * One group mechanism per provider (ADR-0036, Entscheidung 2): a directory run needs an empty
   * groups claim and an OIDC row, and carries an interval between 5 minutes and a week exactly
   * while it is switched on.
   */
  @Test
  void aDirectoryRunNeedsAnOidcRowWithoutGroupsClaimAndAnIntervalWithinItsBounds()
      throws SQLException {
    UUID provider = insertProvider();
    assertThat(
            booleanOf(
                "SELECT directory_sync_enabled FROM oidc_providers WHERE id = '" + provider + "'"))
        .isFalse();

    assertRejected(syncSql(provider, false, "360"), "chk_oidc_providers_directory_sync");
    assertRejected(syncSql(provider, true, "4"), "chk_oidc_providers_directory_sync");
    assertRejected(syncSql(provider, true, "10081"), "chk_oidc_providers_directory_sync");
    execute(syncSql(provider, true, "5"));
    execute(syncSql(provider, true, "10080"));

    execute(syncSql(provider, false, "NULL"));
    execute("UPDATE oidc_providers SET groups_claim = 'groups' WHERE id = '" + provider + "'");
    assertRejected(syncSql(provider, true, "360"), "chk_oidc_providers_directory_sync");

    execute(providerSql(LOCAL_ISSUER, "LOCAL", "NULL", false));
    assertRejected(
        "UPDATE oidc_providers SET directory_sync_enabled = true,"
            + " directory_sync_interval_minutes = 360 WHERE provider_type = 'LOCAL'",
        "chk_oidc_providers_directory_sync");
  }

  // ---------------------------------------------------------------------------------------------
  // Local accounts (ADR-0033)
  // ---------------------------------------------------------------------------------------------

  @Test
  void localCredentialsStartWithTheAdrDefaultsOncePerExistingAccount() throws SQLException {
    UUID user = insertUser(LOCAL_ISSUER, "a@example.org");
    execute(credentialsSql(user, "Sachbearbeitung Meldewesen"));

    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT password_hash, password_change_required, password_change_reason, locked_at,"
                    + " locked_reason, failed_login_attempts, expires_at, is_bootstrap, version"
                    + " FROM local_credentials")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("password_hash")).isNull();
      assertThat(rows.getBoolean("password_change_required")).isFalse();
      assertThat(rows.getString("password_change_reason")).isNull();
      assertThat(rows.getTimestamp("locked_at")).isNull();
      assertThat(rows.getString("locked_reason")).isNull();
      assertThat(rows.getInt("failed_login_attempts")).isZero();
      assertThat(rows.getTimestamp("expires_at")).isNull();
      assertThat(rows.getBoolean("is_bootstrap")).isFalse();
      assertThat(rows.getLong("version")).isZero();
    }
    assertRejected(credentialsSql(user, "Zweite Zeile"), "local_credentials_pkey");
    assertRejected(credentialsSql(UUID.randomUUID(), "Unbekannt"), "fk_local_credentials_user");
  }

  /** ADR-0033, Entscheidung 11: the account is purpose-bound, the purpose is stated. */
  @Test
  void aCreationReasonIsMandatoryNonBlankAndAtMost200Characters() throws SQLException {
    UUID user = insertUser(LOCAL_ISSUER, "a@example.org");

    assertRejected(credentialsSql(user, "   "), "chk_local_credentials_created_reason");
    assertRejected(
        "INSERT INTO local_credentials (user_id) VALUES ('" + user + "')", "created_reason");
    assertRejected(credentialsSql(user, "x".repeat(201)), "value too long");
    execute(credentialsSql(user, "x".repeat(200)));
  }

  @Test
  void passwordChangeAndLockEachCarryAKnownReasonTogetherWithTheirFlag() throws SQLException {
    UUID user = insertUser(LOCAL_ISSUER, "a@example.org");
    execute(credentialsSql(user, "Grund"));
    String where = " WHERE user_id = '" + user + "'";

    assertRejected(
        "UPDATE local_credentials SET password_change_required = true" + where,
        "chk_local_credentials_password_change_consistent");
    assertRejected(
        "UPDATE local_credentials SET password_change_required = true,"
            + " password_change_reason = 'BORED'"
            + where,
        "chk_local_credentials_password_change_reason");
    execute(
        "UPDATE local_credentials SET password_change_required = true,"
            + " password_change_reason = 'ADMIN_RESET'"
            + where);

    assertRejected(
        "UPDATE local_credentials SET locked_at = now()" + where,
        "chk_local_credentials_lock_consistent");
    assertRejected(
        "UPDATE local_credentials SET locked_at = now(), locked_reason = 'WEATHER'" + where,
        "chk_local_credentials_locked_reason");
    execute("UPDATE local_credentials SET locked_at = now(), locked_reason = 'INACTIVITY'" + where);
  }

  @Test
  void failedLoginsNeverGoNegativeAndAtMostOneBootstrapAccountExists() throws SQLException {
    UUID first = insertUser(LOCAL_ISSUER, "a@example.org");
    UUID second = insertUser(LOCAL_ISSUER, "b@example.org");
    execute(credentialsSql(first, "Grund"));
    execute(credentialsSql(second, "Grund"));

    assertRejected(
        "UPDATE local_credentials SET failed_login_attempts = -1 WHERE user_id = '" + first + "'",
        "chk_local_credentials_failed_login_attempts");
    execute("UPDATE local_credentials SET is_bootstrap = true WHERE user_id = '" + first + "'");
    assertRejected(
        "UPDATE local_credentials SET is_bootstrap = true WHERE user_id = '" + second + "'",
        "ux_local_credentials_single_bootstrap");
  }

  /** The local side tables carry no organization of their own and die with their account. */
  @Test
  void deletingAnAccountDeletesItsCredentialsTokensDenylistAndLinks() throws SQLException {
    UUID user = insertUser(LOCAL_ISSUER, "a@example.org");
    execute(credentialsSql(user, "Grund"));
    insertRefreshToken(user, UUID.randomUUID().toString(), null);
    execute(revokedTokenSql(UUID.randomUUID().toString(), user));
    execute(actionTokenSql(user, "SET_PASSWORD", UUID.randomUUID().toString()));

    execute("DELETE FROM users WHERE id = '" + user + "'");

    for (String table :
        new String[] {
          "local_credentials", "local_refresh_tokens", "local_revoked_tokens", "local_action_tokens"
        }) {
      assertThat(countRows(table)).as(table).isZero();
    }
  }

  @Test
  void aRefreshTokenHasAUniqueLookupHashAndExpiresAfterItWasIssued() throws SQLException {
    UUID user = insertUser(LOCAL_ISSUER, "a@example.org");
    insertRefreshToken(user, "hash-1", null);

    assertRejected(refreshTokenSql(UUID.randomUUID(), user, "hash-1", null), "lookup_hash");
    assertRejected(
        "INSERT INTO local_refresh_tokens (id, family_id, user_id, token_lookup_hash, issued_at,"
            + " expires_at, family_expires_at) VALUES (gen_random_uuid(), gen_random_uuid(), '"
            + user
            + "', 'hash-2', now(), now() - interval '1 minute', now() + interval '1 day')",
        "chk_local_refresh_tokens_expiry_order");
  }

  @Test
  void aRefreshTokenRevocationCarriesAKnownReasonTogetherWithItsTimestamp() throws SQLException {
    UUID token = insertRefreshToken(insertUser(LOCAL_ISSUER, "a@example.org"), "hash", null);
    String where = " WHERE id = '" + token + "'";

    assertRejected(
        "UPDATE local_refresh_tokens SET revoked_at = now()" + where,
        "chk_local_refresh_tokens_revocation_consistent");
    assertRejected(
        "UPDATE local_refresh_tokens SET revoked_at = now(), revocation_reason = 'EXPIRED'" + where,
        "chk_local_refresh_tokens_revocation_reason");
    execute(
        "UPDATE local_refresh_tokens SET revoked_at = now(), revocation_reason = 'REUSE_DETECTED'"
            + where);
  }

  @Test
  void theRotationPointerNamesAnExistingTokenAndIsClearedWhenTheSuccessorGoes()
      throws SQLException {
    UUID user = insertUser(LOCAL_ISSUER, "a@example.org");
    UUID successor = insertRefreshToken(user, "successor", null);
    UUID predecessor = insertRefreshToken(user, "predecessor", successor);

    assertRejected(
        refreshTokenSql(UUID.randomUUID(), user, "dangling", UUID.randomUUID()),
        "fk_local_refresh_tokens_rotated_to");
    execute("DELETE FROM local_refresh_tokens WHERE id = '" + successor + "'");
    assertThat(
            countWhere(
                "local_refresh_tokens", "id = '" + predecessor + "' AND rotated_to_id IS NULL"))
        .isEqualTo(1);
  }

  @Test
  void aRevokedJtiIsStoredOnceAndOnlyForAnExistingAccount() throws SQLException {
    UUID user = insertUser(LOCAL_ISSUER, "a@example.org");
    execute(revokedTokenSql("jti-1", user));

    assertRejected(revokedTokenSql("jti-1", user), "local_revoked_tokens_pkey");
    assertRejected(revokedTokenSql("jti-2", UUID.randomUUID()), "fk_local_revoked_tokens_user");
  }

  @Test
  void anActionTokenHasOneOfFourPurposesAndAUniqueHash() throws SQLException {
    UUID user = insertUser(LOCAL_ISSUER, "a@example.org");
    for (String purpose : new String[] {"SET_PASSWORD", "RESET_PASSWORD", "VERIFY_EMAIL"}) {
      execute(actionTokenSql(user, purpose, "hash-" + purpose));
    }

    assertRejected(actionTokenSql(user, "LOGIN", "hash-login"), "chk_local_action_tokens_purpose");
    assertRejected(
        actionTokenSql(user, "VERIFY_EMAIL", "hash-SET_PASSWORD"), "ux_local_action_tokens_hash");
  }

  /**
   * ADR-0033, Entscheidung 12: only a HANDOVER link carries provider and reason, it carries both,
   * and it dies with the provider it was prepared for.
   */
  @Test
  void onlyAHandoverLinkCarriesProviderAndReasonAndItDiesWithTheProvider() throws SQLException {
    UUID user = insertUser(LOCAL_ISSUER, "a@example.org");
    UUID provider = insertProvider();

    assertRejected(actionTokenSql(user, "HANDOVER", "h-1"), "chk_local_action_tokens_handover");
    assertRejected(
        handoverSql(user, "SET_PASSWORD", "h-2", provider, "Wechsel"),
        "chk_local_action_tokens_handover");
    assertRejected(
        handoverSql(user, "HANDOVER", "h-3", provider, null), "chk_local_action_tokens_handover");
    execute(handoverSql(user, "HANDOVER", "h-4", provider, "Wechsel ins Verzeichnis"));

    execute("DELETE FROM oidc_providers WHERE id = '" + provider + "'");
    assertThat(countRows("local_action_tokens")).isZero();
  }

  @Test
  void localAuthSettingsAreASingletonSeededWithTheAdrDefaults() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT id, self_registration_enabled, self_registration_allowed_domains,"
                    + " password_reset_enabled, password_min_length, invitation_token_ttl_hours,"
                    + " reset_token_ttl_minutes, default_expiry_days, inactive_days, updated_by,"
                    + " version FROM local_auth_settings")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt("id")).isEqualTo(1);
      assertThat(rows.getBoolean("self_registration_enabled")).isFalse();
      assertThat(rows.getString("self_registration_allowed_domains")).isEmpty();
      assertThat(rows.getBoolean("password_reset_enabled")).isTrue();
      assertThat(rows.getInt("password_min_length")).isEqualTo(12);
      assertThat(rows.getInt("invitation_token_ttl_hours")).isEqualTo(72);
      assertThat(rows.getInt("reset_token_ttl_minutes")).isEqualTo(30);
      assertThat(rows.getInt("default_expiry_days")).isEqualTo(90);
      assertThat(rows.getInt("inactive_days")).isEqualTo(90);
      assertThat(rows.getObject("updated_by")).isNull();
      assertThat(rows.getLong("version")).isZero();
      assertThat(rows.next()).as("exactly one row").isFalse();
    }
    assertRejected(
        "INSERT INTO local_auth_settings (id) VALUES (2)", "chk_local_auth_settings_singleton");
  }

  /** The bounds ADR-0033 names are CHECKs, so no write path can weaken them. */
  @Test
  void localAuthPoliciesStayWithinTheirAdrBounds() throws SQLException {
    assertPolicyBounds("password_min_length", 8, 64, "chk_local_auth_settings_password_min_length");
    assertPolicyBounds(
        "reset_token_ttl_minutes", 1, 1440, "chk_local_auth_settings_reset_token_ttl");
    assertPolicyBounds(
        "invitation_token_ttl_hours", 1, 720, "chk_local_auth_settings_invitation_token_ttl");
    assertRejected(
        "UPDATE local_auth_settings SET inactive_days = 29",
        "chk_local_auth_settings_inactive_days");
    assertRejected(
        "UPDATE local_auth_settings SET default_expiry_days = 0",
        "chk_local_auth_settings_default_expiry_days");
    execute("UPDATE local_auth_settings SET inactive_days = 30, default_expiry_days = 1");
  }

  /** The settings row is never deleted, so it outlives the administrator who last changed it. */
  @Test
  void localAuthSettingsOutliveTheirLastEditor() throws SQLException {
    UUID admin = insertUser();
    execute("UPDATE local_auth_settings SET updated_by = '" + admin + "'");

    execute("DELETE FROM users WHERE id = '" + admin + "'");

    assertThat(countWhere("local_auth_settings", "updated_by IS NULL")).isEqualTo(1);
  }

  /**
   * Written by the seeder in the transaction of the account it creates, never by the baseline, so
   * "never attempted" stays distinguishable from "attempted".
   */
  @Test
  void theBootstrapSeedMarkerStartsEmptyAndHoldsOnlyTheSingletonRow() throws SQLException {
    assertThat(countRows("local_admin_seed_marker")).isZero();
    execute("INSERT INTO local_admin_seed_marker (id, seeded_at) VALUES (1, now())");
    assertRejected(
        "INSERT INTO local_admin_seed_marker (id, seeded_at) VALUES (2, now())",
        "chk_local_admin_seed_marker_singleton");
  }

  // ---------------------------------------------------------------------------------------------
  // Mail, branding, notifications
  // ---------------------------------------------------------------------------------------------

  /** A fresh installation sends nothing: the row exists so the service never has to create it. */
  @Test
  void mailSettingsAreASingletonSeededSwitchedOffAndUnconfigured() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT id, enabled, encryption, host, password_ciphertext FROM mail_settings")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt("id")).isEqualTo(1);
      assertThat(rs.getBoolean("enabled")).isFalse();
      assertThat(rs.getString("encryption")).isEqualTo("STARTTLS");
      assertThat(rs.getString("host")).isNull();
      assertThat(rs.getString("password_ciphertext")).isNull();
      assertThat(rs.next()).isFalse();
    }
    assertRejected(
        "INSERT INTO mail_settings (id, updated_at) VALUES (2, now())",
        "chk_mail_settings_singleton");
  }

  @Test
  void mailSettingsKnowThreeEncryptionModesAndAValidPortOrNone() throws SQLException {
    assertRejected("UPDATE mail_settings SET encryption = 'TLS13'", "chk_mail_settings_encryption");
    assertRejected("UPDATE mail_settings SET port = 0", "chk_mail_settings_port");
    assertRejected("UPDATE mail_settings SET port = 65536", "chk_mail_settings_port");
    execute("UPDATE mail_settings SET encryption = 'SSL', port = 465");
    execute("UPDATE mail_settings SET encryption = 'NONE', port = NULL");
  }

  /** Only overrides live here; the HTML body is optional so the branded frame stays the default. */
  @Test
  void aMailTemplateOverrideIsUniquePerKeyAndLocaleAndNeedsNoHtmlBody() throws SQLException {
    execute(mailTemplateSql("INVITATION", "de"));
    execute(mailTemplateSql("INVITATION", "en"));

    assertRejected(mailTemplateSql("INVITATION", "de"), "ux_mail_templates_key_locale");
    assertThat(countWhere("mail_templates", "body_html IS NULL")).isEqualTo(2);
  }

  /**
   * The two sign-in images share the logo's shape - all four columns or none, PNG or JPEG only -
   * and each carries its own size ceiling.
   */
  @Test
  void eachSignInImageSlotIsAllOrNothingPngOrJpegAndBoundedInSize() throws SQLException {
    for (String slot : new String[] {"login_logo", "login_background"}) {
      assertRejected(
          "UPDATE branding_settings SET " + slot + "_content = '\\x89'::bytea",
          "chk_branding_settings_" + slot + "_complete");
      assertRejected(
          imageSql(slot, "image/gif", 16), "chk_branding_settings_" + slot + "_content_type");
    }
    execute(imageSql("login_logo", "image/png", 524288));
    assertRejected(
        imageSql("login_logo", "image/png", 524289), "chk_branding_settings_login_logo_size");
    execute(imageSql("login_background", "image/jpeg", 2097152));
    assertRejected(
        imageSql("login_background", "image/jpeg", 2097153),
        "chk_branding_settings_login_background_size");
  }

  @Test
  void deletingTheRecipientDeletesTheirNotifications() throws SQLException {
    UUID recipient = insertUser();
    execute(
        "INSERT INTO notifications (id, organization_id, recipient_user_id, type, title) VALUES"
            + " (gen_random_uuid(), '"
            + SEEDED_ORGANIZATION_ID
            + "', '"
            + recipient
            + "', 'LIBRARY_ASSOCIATED_TO_MIXED_SPACE', 'Titel')");

    execute("DELETE FROM users WHERE id = '" + recipient + "'");

    assertThat(countWhere("notifications", "recipient_user_id = '" + recipient + "'")).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // Audit trail
  // ---------------------------------------------------------------------------------------------

  /** An entry names its object from the closed list, which knows prompt libraries and prompts. */
  @Test
  void theAuditLogAcceptsThePromptObjectsAndRefusesAnUnknownObjectType() throws SQLException {
    execute(auditEntrySql("PROMPT_LIBRARY"));
    execute(auditEntrySql("PROMPT"));

    assertRejected(auditEntrySql("PROMPT_TEMPLATE"), "chk_audit_log_object_type");
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private UUID insertUser(String issuer, String email) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(insertUserSql(id, issuer, email));
    return id;
  }

  private static String insertUserSql(String issuer, String email) {
    return insertUserSql(UUID.randomUUID(), issuer, email);
  }

  private static String insertUserSql(UUID id, String issuer, String email) {
    return "INSERT INTO users (id, subject, issuer, email, organization_id) VALUES ('"
        + id
        + "', '"
        + id
        + "', '"
        + issuer
        + "', '"
        + email
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "')";
  }

  private static String providerSql(
      String issuer, String providerType, String clientIdLiteral, boolean isDefault) {
    return "INSERT INTO oidc_providers (id, display_name, is_default, issuer_uri, client_id,"
        + " provider_type, is_external) VALUES (gen_random_uuid(), 'Anbieter', "
        + isDefault
        + ", '"
        + issuer
        + "', "
        + clientIdLiteral
        + ", '"
        + providerType
        + "', false)";
  }

  private static String syncSql(UUID provider, boolean enabled, String intervalLiteral) {
    return "UPDATE oidc_providers SET directory_sync_enabled = "
        + enabled
        + ", directory_sync_interval_minutes = "
        + intervalLiteral
        + " WHERE id = '"
        + provider
        + "'";
  }

  private static String credentialsSql(UUID user, String reason) {
    return "INSERT INTO local_credentials (user_id, created_reason) VALUES ('"
        + user
        + "', '"
        + reason
        + "')";
  }

  private UUID insertRefreshToken(UUID user, String lookupHash, UUID rotatedTo)
      throws SQLException {
    UUID id = UUID.randomUUID();
    execute(refreshTokenSql(id, user, lookupHash, rotatedTo));
    return id;
  }

  private static String refreshTokenSql(UUID id, UUID user, String lookupHash, UUID rotatedTo) {
    return "INSERT INTO local_refresh_tokens (id, family_id, user_id, token_lookup_hash, issued_at,"
        + " expires_at, family_expires_at, rotated_to_id) VALUES ('"
        + id
        + "', gen_random_uuid(), '"
        + user
        + "', '"
        + lookupHash
        + "', now(), now() + interval '1 hour', now() + interval '1 day', "
        + quoted(rotatedTo)
        + ")";
  }

  private static String revokedTokenSql(String jtiHash, UUID user) {
    return "INSERT INTO local_revoked_tokens (jti_hash, user_id, expires_at) VALUES ('"
        + jtiHash
        + "', '"
        + user
        + "', now() + interval '1 hour')";
  }

  private static String actionTokenSql(UUID user, String purpose, String hash) {
    return handoverSql(user, purpose, hash, null, null);
  }

  private static String handoverSql(
      UUID user, String purpose, String hash, UUID provider, String reason) {
    return "INSERT INTO local_action_tokens (id, user_id, purpose, token_hash, expires_at,"
        + " provider_id, reason) VALUES (gen_random_uuid(), '"
        + user
        + "', '"
        + purpose
        + "', '"
        + hash
        + "', now() + interval '1 day', "
        + quoted(provider)
        + ", "
        + quoted(reason)
        + ")";
  }

  private void assertPolicyBounds(String column, int lowest, int highest, String constraint)
      throws SQLException {
    assertRejected("UPDATE local_auth_settings SET " + column + " = " + (lowest - 1), constraint);
    assertRejected("UPDATE local_auth_settings SET " + column + " = " + (highest + 1), constraint);
    execute("UPDATE local_auth_settings SET " + column + " = " + lowest);
    execute("UPDATE local_auth_settings SET " + column + " = " + highest);
  }

  private static String mailTemplateSql(String key, String locale) {
    return "INSERT INTO mail_templates (id, template_key, locale, subject, body_plain) VALUES"
        + " (gen_random_uuid(), '"
        + key
        + "', '"
        + locale
        + "', 'Betreff', 'Text')";
  }

  private static String imageSql(String slot, String contentType, int bytes) {
    return "UPDATE branding_settings SET "
        + slot
        + "_content = decode(repeat('00', "
        + bytes
        + "), 'hex'), "
        + slot
        + "_content_type = '"
        + contentType
        + "', "
        + slot
        + "_version = 'v1', "
        + slot
        + "_updated_at = now()";
  }

  private static String auditEntrySql(String objectType) {
    return "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
        + " event_type, object_type, object_id, outcome) VALUES (gen_random_uuid(), now(), '"
        + SEEDED_ORGANIZATION_ID
        + "', 'USER', 'pseudonym', 'PROMPT_CREATED', '"
        + objectType
        + "', 'id', 'SUCCESS')";
  }
}
