package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The security invariants of {@link SecurityInvariants} over the backend, each with its allowlist
 * of justified exceptions; an entry the code outgrew fails as well, so every list only shrinks.
 * {@link SecurityInvariantsFixtureTest} proves that each rule fires.
 *
 * <p>The whole-row rule sees calls, not data flow: an entry covers every write of its code unit,
 * and whether an instance is new, loaded or detached is the reason's claim, not the rule's. A type
 * enters {@link #SECURITY_STATE_ENTITIES} by hand; the field names of {@code
 * SecurityInvariants#SECURITY_STATE_FIELD} only keep a new one from going unclassified, so a type
 * whose state hides behind another name, or that is only inserted and deleted ({@code
 * GroupMembership}, {@code CapabilityGrant}), is not asked about.
 */
class SecurityInvariantsTest {

  private static final String MODEL_ADDRESS_CHECKED =
      "ModelEndpointAccess or the administration checks the address with ModelAddressPolicy before"
          + " it is stored or used; follows no redirect";

  private static final String CLAMD_DAEMON =
      "the clamd daemon is system administrator configuration (MalwareScanSettingsService), set by"
          + " a system administrator or by the installation step, which takes only port 3310; a"
          + " scanner in the internal network is the rule, so no address policy refuses one";

  private static final String SMTP_SERVER =
      "the SMTP server is system administrator configuration (MailSettingsService), checked as"
          + " STORED_TARGET by StoredTargetWarmup and InstallationMailTargets (ADR-0063); an internal"
          + " server is the rule, so no address policy refuses one";

  /**
   * Code units that connect without {@link SecurityInvariants#TARGET_CHECKING_BLOCKS}, each with
   * the check its target passes instead. Model and identity provider addresses have checks of their
   * own (ADR-0060, ADR-0025); SIEM receiver, virus scanner and SMTP server are fixed by the
   * operator.
   */
  static final Map<String, String> CONNECTS_PAST_THE_BLOCKS =
      Map.ofEntries(
          Map.entry(
              "io.opaa.auth.OidcDiscoveryClient#fetch",
              "checks the issuer and the jwks_uri of the discovery document with OidcAddressPolicy"
                  + " before each request; follows no redirect"),
          Map.entry(
              "io.opaa.auth.NimbusOidcJwtDecoderFactory#create",
              "reads the JWK set at the stored override or the jwks_uri OidcDiscoveryClient"
                  + " checked, through a client that follows no redirect"),
          Map.entry(
              "io.opaa.directory.sync.keycloak.KeycloakAdminApi#send",
              "ProviderDirectoryClient and DirectoryConnectorService check the admin API address"
                  + " with OidcAddressPolicy before each run and probe; follows no redirect"),
          Map.entry(
              "io.opaa.indexing.source.smb.SmbShareClient#signIn",
              "smbj connects through ValidatingSocketFactory, which checks every address"),
          Map.entry(
              "io.opaa.indexing.source.smb.SmbShareClient$ValidatingSocketFactory#createSocket",
              "runs TargetAddressValidator#validateHost before it connects"),
          Map.entry(
              "io.opaa.observability.MeteredHttpClient#send",
              "forwards a request its caller built and checked to the wrapped client unchanged;"
                  + " the decorator only measures, its callers stand in this list or send through"
                  + " the blocks"),
          Map.entry(
              "io.opaa.observability.MeteredHttpClient#sendAsync",
              "forwards a request its caller built and checked to the wrapped client unchanged;"
                  + " the decorator only measures"),
          Map.entry("io.opaa.llm.embedding.HttpEmbeddingProbe#exchange", MODEL_ADDRESS_CHECKED),
          Map.entry(
              "io.opaa.query.sandbox.OpenSandboxClient#send",
              "reaches only the sandbox server the operator configured in opaa.sandbox.base-url,"
                  + " never an address from the model or a person; follows no redirect"),
          Map.entry("io.opaa.llm.LlmModelConnectionTester#probe", MODEL_ADDRESS_CHECKED),
          Map.entry("io.opaa.llm.LlmModelDiscoverer#send", MODEL_ADDRESS_CHECKED),
          Map.entry(
              "io.opaa.llm.ModelRedirectRefusal#intercept",
              "re-issues the model client's own request with redirects off; the endpoint was"
                  + " checked with ModelAddressPolicy when it was stored"),
          Map.entry("io.opaa.llm.rerank.RerankClient#send", MODEL_ADDRESS_CHECKED),
          Map.entry("io.opaa.llm.transcription.TranscriptionClient#send", MODEL_ADDRESS_CHECKED),
          Map.entry(
              "io.opaa.llm.transcription.RealtimeTranscriptionClient$Connection#connect",
              MODEL_ADDRESS_CHECKED),
          Map.entry(
              "io.opaa.llm.textrecognition.TextRecognitionClient#exchange", MODEL_ADDRESS_CHECKED),
          Map.entry(
              "io.opaa.audit.SyslogSender#deliver",
              "the SIEM receiver comes from the operator's configuration (opaa.siem-export.*),"
                  + " never from a request"),
          Map.entry(
              "io.opaa.audit.SyslogSender#secured",
              "wraps the socket deliver connected to the configured SIEM receiver in TLS"),
          Map.entry("io.opaa.knowledge.ClamdClient#scan", CLAMD_DAEMON),
          Map.entry("io.opaa.knowledge.ClamdClient#version", CLAMD_DAEMON),
          Map.entry("io.opaa.mail.MailConnectionTester#test", SMTP_SERVER),
          Map.entry("io.opaa.mail.MailService#attemptSend", SMTP_SERVER),
          Map.entry("io.opaa.mail.MailService#deliver", SMTP_SERVER),
          Map.entry("io.opaa.mail.MailService#sendThrough", SMTP_SERVER));

  private static final String NO_CREDENTIAL_LABEL =
      "the account label shown to its owner; no credential";

  private static final String WIZARD_STEP_KEY =
      "the key of a saved wizard step, used only with the address saved with it"
          + " (StepRules#sameEndpoint)";

  private static final String OWN_ADDRESS_AND_KEY =
      "builds the client of a stored model from its own address and key";

  /**
   * Code units that decrypt a stored value without {@link
   * SecurityInvariants#SECRET_BINDING_BLOCKS}, each with what binds the secret to its target, or
   * why the value is no credential.
   */
  static final Map<String, String> DECRYPTS_PAST_THE_BLOCKS =
      Map.ofEntries(
          Map.entry(
              "io.opaa.connection.account.ConnectedAccountSelfDisclosureExporter#label",
              NO_CREDENTIAL_LABEL),
          Map.entry(
              "io.opaa.connection.account.ConnectedAccountService#labelOf", NO_CREDENTIAL_LABEL),
          Map.entry(
              "io.opaa.connection.oauth.ConnectionAuthorizationService#verifierOf",
              "the PKCE verifier of a pending sign-in, sent only with the code exchange of that"
                  + " sign-in"),
          Map.entry(
              "io.opaa.connection.profile.ProfileRegistrations#secretOf",
              "the client secret of a profile, registered only with that profile's own endpoints"
                  + " for OAuthClient"),
          Map.entry(
              "io.opaa.connection.token.GrantRevocations#revoke",
              "the revocation order of a discarded grant, sent to the provider that issued it"),
          Map.entry(
              "io.opaa.directory.sync.connector.DirectoryConnector#readableSecret",
              "the client secret, bound to its row, goes only to the connector that signs in with"
                  + " it at the stored admin address"),
          Map.entry(
              "io.opaa.directory.sync.connector.DirectoryConnector#getClientSecret",
              "the same secret for ProviderDirectoryClient, which signs in only at the admin address"
                  + " it checked, and DirectoryConnectorService, which probes with the stored one"
                  + " only at the stored address"),
          Map.entry(
              "io.opaa.knowledge.KnowledgeLibrary#readQuietly",
              "credentials and push secret, bound to the library's row; the credentials leave only"
                  + " through the secret port, which follows SecretTarget, the push secret only"
                  + " checks incoming notifications"),
          Map.entry(
              "io.opaa.knowledge.KnowledgeLibrary#sealed",
              "compares a new value with the stored one to keep an unchanged ciphertext; the value"
                  + " leaves the method only as that comparison"),
          Map.entry(
              "io.opaa.installation.InstallationModelDiscovery#effectiveKey", WIZARD_STEP_KEY),
          Map.entry(
              "io.opaa.installation.StepRules#apiKey",
              "turns a saved wizard step, address and key stored together, into the settings the"
                  + " installation writes, and hands the check probe of a model step"
                  + " (StepChecks#withKey, WIZARD_STEP_KEY) its key"),
          Map.entry(
              "io.opaa.installation.StepRules#mailSettings",
              "turns the saved mail step, server and password stored together, into the mail"
                  + " settings the installation writes"),
          Map.entry(
              "io.opaa.installation.StepRules#mailSettingsUpdate",
              "turns the saved mail step, server and password stored together, into the mail"
                  + " settings the installation writes"),
          Map.entry("io.opaa.llm.ActiveChatModelResolver#chatModelBuilder", OWN_ADDRESS_AND_KEY),
          Map.entry(
              "io.opaa.llm.embedding.EmbeddingModelAdministration#decrypt",
              "reuses the installed model's key for a draft only at the stored address"
                  + " (ModelEndpointRules#sameEndpoint)"),
          Map.entry("io.opaa.llm.embedding.InstalledEmbeddingModel#build", OWN_ADDRESS_AND_KEY),
          Map.entry(
              "io.opaa.llm.administration.ModelRoleAdministration#decryptForTest",
              "reuses the stored live key for a test only at the stored live address"
                  + " (ModelEndpointRules#sameEndpoint) and the batch key only at the batch"
                  + " address's origin (RealtimeTranscriptionEndpoint#sharesOrigin)"),
          Map.entry(
              "io.opaa.llm.administration.ModelRoleAdministration#testKey",
              "reuses a role's stored key for a test only at the stored address"
                  + " (ModelEndpointRules#sameEndpoint)"),
          Map.entry(
              "io.opaa.llm.OptionalRoleEndpoints#decrypt",
              "loads the rerank, transcription and text recognition endpoints, each with its own"
                  + " address and key; the live upstream borrows the batch key only at its origin"),
          Map.entry(
              "io.opaa.llm.rerank.RerankEndpoints#load",
              "the rerank key, bound to its row, goes into the endpoint with the stored address"),
          Map.entry(
              "io.opaa.llm.textrecognition.TextRecognitionEndpoints#load",
              "the text recognition key, bound to its row, goes into the endpoint with the stored"
                  + " address"),
          Map.entry(
              "io.opaa.llm.transcription.TranscriptionEndpoints#load",
              "the transcription key, bound to its row, goes into the endpoint with the stored"
                  + " address"),
          Map.entry(
              "io.opaa.llm.transcription.TranscriptionEndpoints#realtimeOf",
              "the live key goes to the stored live address; without one the batch key goes only"
                  + " to the batch address's origin (RealtimeTranscriptionEndpoint#sharesOrigin)"),
          Map.entry(
              "io.opaa.mail.MailOutbox#decrypt",
              "queued mails (recipient, subject, body); no credential"),
          Map.entry(
              "io.opaa.mail.MailSettingsService#toSnapshot",
              "the SMTP password; requirePasswordFollowsTarget keeps it only while host, port,"
                  + " user and encryption stay"));

  /**
   * Code units of {@code io.opaa.format} that call a member of {@link
   * SecurityInvariants#PER_CALL_REGEX_MEMBERS}, each with why it compiles nothing.
   */
  static final Map<String, String> PER_CALL_REGEX_IN_FORMAT =
      Map.ofEntries(
          Map.entry(
              "io.opaa.format.FormatMetadataField#<init>",
              "enum constructor: runs once per constant while the class initializes"),
          Map.entry(
              "io.opaa.format.FormatAdmission#requireNormalized",
              "splits on one plain character: String's fast path, no pattern"),
          Map.entry(
              "io.opaa.format.SupportedDocumentFormats#contentMatchesExtension",
              "splits on one plain character: String's fast path, no pattern"),
          Map.entry(
              "io.opaa.format.SupportedDocumentFormats#extensionForContentType",
              "splits on one plain character: String's fast path, no pattern"),
          Map.entry(
              "io.opaa.format.SupportedDocumentFormats#extensionForDetectedContent",
              "splits on one plain character: String's fast path, no pattern"),
          Map.entry(
              "io.opaa.format.SupportedDocumentFormats#isUnresolvedContainerType",
              "splits on one plain character: String's fast path, no pattern"),
          Map.entry(
              "io.opaa.format.file.markdown.MarkdownDocumentFormat#frontmatterEntries",
              "splits on one plain character: String's fast path, no pattern"),
          Map.entry(
              "io.opaa.format.file.markdown.MarkdownDocumentFormat#stripLeadingFrontmatter",
              "splits on one plain character: String's fast path, no pattern"),
          Map.entry(
              "io.opaa.format.file.markdown.MarkdownDocumentFormat#toEvents",
              "splits on one plain character: String's fast path, no pattern"));

  /**
   * The persistent types that carry security state, with that state. A save or change of one of
   * them stands in {@link #WHOLE_ROW_WRITERS} unless {@code @Version} guards its rows.
   */
  static final Map<String, String> SECURITY_STATE_ENTITIES =
      Map.ofEntries(
          Map.entry("io.opaa.auth.User", "system role, directory lock, erasure"),
          Map.entry(
              "io.opaa.auth.LocalCredentials",
              "lock, failed-login lockout, expiry, session generation"),
          Map.entry(
              "io.opaa.auth.OidcProvider",
              "whether the provider signs anyone in, the claims that decide role and groups"),
          Map.entry("io.opaa.account.LocalRefreshToken", "revocation and end of a session family"),
          Map.entry("io.opaa.account.LocalActionToken", "consumption and expiry of a mailed link"),
          Map.entry("io.opaa.account.LocalRevokedToken", "the denylist of access tokens"),
          Map.entry("io.opaa.account.LocalAuthSettings", "the lockout rules of the local sign-in"),
          Map.entry(
              "io.opaa.externalaccess.ExternalAccessSettings",
              "the installation switch of the external access"),
          Map.entry("io.opaa.externalaccess.token.ExternalAccessToken", "revocation, expiry"),
          Map.entry("io.opaa.externalaccess.token.ServiceAccountToken", "revocation, expiry"),
          Map.entry("io.opaa.externalaccess.token.ServiceAccount", "deactivation"),
          Map.entry(
              "io.opaa.externalaccess.token.ServiceAccountLibraryConsent",
              "end and expiry of a consent"),
          Map.entry(
              "io.opaa.diagnosticaccess.DiagnosticImpersonationGrant",
              "revocation and validity of an impersonation grant"),
          Map.entry(
              "io.opaa.group.Group", "deactivation, dissolution, release, protection of a group"),
          Map.entry("io.opaa.permission.AssetGrant", "role and expiry of a grant"),
          Map.entry("io.opaa.space.SpaceMembership", "role in a space"),
          Map.entry(
              "io.opaa.knowledge.KnowledgeLibrary", "release for external access and its end"),
          Map.entry(
              "io.opaa.connection.oauth.ConnectionAuthorization",
              "consumption and expiry of a pending sign-in"),
          Map.entry("io.opaa.connection.profile.ConnectorTypePolicy", "lock of a connector type"),
          Map.entry("io.opaa.connection.profile.ConnectionProfile", "lock of a profile"),
          Map.entry("io.opaa.connection.profile.LibraryConnection", "end of a connection"),
          Map.entry("io.opaa.connection.token.ConnectionToken", "expiry of a stored token"));

  /**
   * Persistent types with a field named like security state that carry none, each with what the
   * field means instead.
   */
  static final Map<String, String> NOT_SECURITY_STATE =
      Map.ofEntries(
          Map.entry("io.opaa.agent.AgentSettings", "enabled switches a feature, grants no access"),
          Map.entry(
              "io.opaa.asset.AssetVisibilityHistory",
              "history of the external access for the audit; no decision reads it"),
          Map.entry(
              "io.opaa.permission.AssetGrantHistory",
              "history of grants for the audit; no decision reads it"),
          Map.entry(
              "io.opaa.space.SpaceMembershipHistory",
              "history of memberships for the audit; no decision reads it"),
          Map.entry("io.opaa.chat.ChatMessage", "role is the author of a chat message"),
          Map.entry(
              "io.opaa.chat.LockedAccountChatCleanupSetting",
              "enabled switches the deletion of chats of locked accounts"),
          Map.entry(
              "io.opaa.connection.account.ConnectionPersonState",
              "when the reconciler last saw the account deactivated or dormant; the account's own"
                  + " state decides access"),
          Map.entry(
              "io.opaa.knowledge.MalwareScanSettings",
              "enabled switches the virus scan; one administrator form, written only as a whole"),
          Map.entry(
              "io.opaa.mail.MailSettings",
              "enabled switches sending; one administrator form, written only as a whole"),
          Map.entry("io.opaa.llm.LlmModel", "active marks the model in use"),
          Map.entry("io.opaa.llm.OptionalRoleSettings", "enabled switches a model role"),
          Map.entry("io.opaa.llm.rerank.RerankModelSettings", "enabled switches a model role"),
          Map.entry(
              "io.opaa.llm.textrecognition.TextRecognitionModelSettings",
              "enabled switches a model role"),
          Map.entry(
              "io.opaa.llm.transcription.TranscriptionModelSettings",
              "enabled switches a model role"));

  private static final String NEW_ROW =
      "inserts an instance it constructed itself; there is no earlier read to write back";

  private static final String CHANGED_COLUMNS_IN_TRANSACTION =
      "loads and saves the instance in one transaction; @DynamicUpdate writes only the columns it"
          + " changed";

  private static final String TOWARD_REVOKED =
      "only moves the row toward revoked or ended; what it may write back stale (use day, lapse or"
          + " reminder marker) grants no access";

  private static final String ROLE_ONLY =
      "the role is the only state that changes after creation, so the whole row is that write; a"
          + " row deleted meanwhile fails the update instead of returning";

  /**
   * Code units that may write a stale whole row of {@link #SECURITY_STATE_ENTITIES}, each with why
   * no security state committed by another transaction is lost.
   */
  static final Map<String, String> WHOLE_ROW_WRITERS =
      Map.ofEntries(
          Map.entry(
              "io.opaa.account.AccountErasureService#erase",
              "locks the users and local_credentials rows FOR UPDATE first and reads the row afresh"
                  + " under that lock"),
          Map.entry("io.opaa.account.InitialAdministrator#createBootstrapUser", NEW_ROW),
          Map.entry("io.opaa.account.InitialAdministrator#ensureLocalProviderRow", NEW_ROW),
          Map.entry(
              "io.opaa.account.LocalActionTokenService#issue",
              NEW_ROW + "; consumption is a conditional update"),
          Map.entry(
              "io.opaa.account.LocalActionTokenService#issueHandover",
              NEW_ROW + "; consumption is a conditional update"),
          Map.entry("io.opaa.account.LocalAdminReset#reset", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.account.LocalHandoverAccountService#redeem", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.account.LocalPasswordService#confirmRegistration",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.account.LocalRefreshTokenService#insert",
              NEW_ROW + "; revocation and rotation are targeted updates"),
          Map.entry("io.opaa.account.LocalSelfServiceAccountService#register", NEW_ROW),
          Map.entry("io.opaa.account.LocalTokenRevocationService#revokeAccessToken", NEW_ROW),
          Map.entry("io.opaa.account.LocalUserService#create", NEW_ROW),
          Map.entry("io.opaa.account.LocalUserService#update", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry("io.opaa.asset.AssetGrantService#grantAtCreation", NEW_ROW),
          Map.entry("io.opaa.asset.AssetGrantService#upsertGrant", ROLE_ONLY),
          Map.entry("io.opaa.asset.AssetShellOwnershipDirectory#giveOwnerGrant", ROLE_ONLY),
          Map.entry("io.opaa.asset.AssetShellOwnershipDirectory#moveOwnerGrant", ROLE_ONLY),
          Map.entry(
              "io.opaa.auth.UserService#applyRole",
              CHANGED_COLUMNS_IN_TRANSACTION + " (updateRole, confirmAuditorRequest)"),
          Map.entry("io.opaa.auth.UserService#insertUser", NEW_ROW),
          Map.entry(
              "io.opaa.connection.LibraryConnectionService#connect",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.connection.LibraryConnectionService#disconnect",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry("io.opaa.connection.oauth.ConnectionAuthorizationService#start", NEW_ROW),
          Map.entry(
              "io.opaa.connection.profile.ConnectionProfileService#update",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.connection.profile.ConnectorLockService#lockType",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.connection.profile.ProfileRequirementService#require",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry("io.opaa.diagnosticaccess.DiagnosticImpersonationGrantService#grant", NEW_ROW),
          Map.entry(
              "io.opaa.diagnosticaccess.DiagnosticImpersonationGrantService#revoke",
              TOWARD_REVOKED),
          Map.entry(
              "io.opaa.diagnosticaccess.DiagnosticImpersonationGrantService#revokeGrantsIssuedBy",
              TOWARD_REVOKED),
          Map.entry(
              "io.opaa.diagnosticaccess.LibraryDiagnosticsLockService#setLocked",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry("io.opaa.directory.OidcProviderService#createInitialProvider", NEW_ROW),
          Map.entry("io.opaa.directory.OidcProviderService#createProvider", NEW_ROW),
          Map.entry(
              "io.opaa.directory.OidcProviderService#enableLocalAccountsForInstallation",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.directory.OidcProviderService#makeDefault", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.directory.OidcProviderService#renameLocalRow",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.directory.OidcProviderService#reorder", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.directory.OidcProviderService#setDirectorySync",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.directory.OidcProviderService#setEnabled", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.directory.OidcProviderService#setExternal", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.directory.OidcProviderService#setLocalAccountsEnabled",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.directory.OidcProviderService#switchLocalAccounts",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.directory.OidcProviderService#updateProvider",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.directory.sync.DirectorySyncPlanExecutor#applyAccountStates",
              CHANGED_COLUMNS_IN_TRANSACTION + " of the sync run"),
          Map.entry(
              "io.opaa.directory.sync.DirectorySyncPlanExecutor#applyPlan",
              CHANGED_COLUMNS_IN_TRANSACTION + " of the sync run; new groups are inserted"),
          Map.entry(
              "io.opaa.directory.sync.DirectorySyncPlanExecutor#resolveAndApplyParentLinks",
              CHANGED_COLUMNS_IN_TRANSACTION + " of the sync run"),
          Map.entry(
              "io.opaa.externalaccess.token.ExternalAccessTokenAdminService#block", TOWARD_REVOKED),
          Map.entry(
              "io.opaa.externalaccess.token.ExternalAccessTokenAdminService#blockAllOf",
              TOWARD_REVOKED),
          Map.entry(
              "io.opaa.externalaccess.token.ExternalAccessTokenAdminService"
                  + "#blockAllOfServiceAccount",
              TOWARD_REVOKED),
          Map.entry(
              "io.opaa.externalaccess.token.ExternalAccessTokenAdminService"
                  + "#blockServiceAccountToken",
              TOWARD_REVOKED),
          Map.entry(
              "io.opaa.externalaccess.token.ExternalAccessTokenLapseService#endTokensOfAccount",
              TOWARD_REVOKED),
          Map.entry(
              "io.opaa.externalaccess.token.ExternalAccessTokenLapseService#recordLapse",
              "reads the token FOR UPDATE (findForLapse) and only moves it toward revoked"),
          Map.entry(
              "io.opaa.externalaccess.token.ExternalAccessTokenScopeService#effectiveLibraryIds",
              "changes only the library selection, rows of a collection table; no column of the"
                  + " token row changes, so Hibernate writes none"),
          Map.entry("io.opaa.externalaccess.token.ExternalAccessTokenService#issue", NEW_ROW),
          Map.entry(
              "io.opaa.externalaccess.token.ExternalAccessTokenService#revokeOwn", TOWARD_REVOKED),
          Map.entry(
              "io.opaa.externalaccess.token.ServiceAccountConsentService#revoke", TOWARD_REVOKED),
          Map.entry(
              "io.opaa.externalaccess.token.ServiceAccountConsentService#set",
              TOWARD_REVOKED + "; the replacing consent is a new row"),
          Map.entry(
              "io.opaa.externalaccess.token.ServiceAccountService#deactivate", TOWARD_REVOKED),
          Map.entry("io.opaa.externalaccess.token.ServiceAccountService#issueToken", NEW_ROW),
          Map.entry(
              "io.opaa.externalaccess.token.ServiceAccountService#revokeToken", TOWARD_REVOKED),
          Map.entry("io.opaa.group.GroupDeactivation#deactivate", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry("io.opaa.group.GroupDeactivation#reactivate", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.group.GroupService#addMember",
              CHANGED_COLUMNS_IN_TRANSACTION + "; only the membership collection changes"),
          Map.entry("io.opaa.group.GroupService#createGroup", NEW_ROW),
          Map.entry(
              "io.opaa.group.GroupService#removeMember",
              CHANGED_COLUMNS_IN_TRANSACTION + "; only the membership collection changes"),
          Map.entry("io.opaa.group.GroupService#setProtection", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry("io.opaa.group.GroupService#setRelease", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry("io.opaa.group.GroupService#updateGroup", CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry("io.opaa.group.TokenGroupSynchronizer#findOrCreate", NEW_ROW),
          Map.entry("io.opaa.library.KnowledgeLibraryService#createLibrary", NEW_ROW),
          Map.entry(
              "io.opaa.library.KnowledgeLibraryService#generatePushSecret",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.library.KnowledgeLibraryService#removePushSecret",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.library.KnowledgeLibraryService#updateLibrary",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.library.LibraryExternalAccessExpiryService#runOnce",
              "locks the due rows FOR UPDATE first; " + CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.library.LibraryExternalAccessReminderService#runOnce",
              "locks the due rows FOR UPDATE first; " + CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.library.LibraryExternalAccessService#setExternalAccess",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry("io.opaa.library.PrivateLibraryCreation#create", NEW_ROW),
          Map.entry(
              "io.opaa.metadata.LibraryMetadataExtractionService#updateSettings",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry(
              "io.opaa.metadata.LibraryMetadataFieldService#updateCoreContextPrefix",
              CHANGED_COLUMNS_IN_TRANSACTION),
          Map.entry("io.opaa.permission.PermissionTransferService#transferGrants", ROLE_ONLY),
          Map.entry(
              "io.opaa.space.GroupSpaceMembershipDirectoryAdapter#transferSpaceMemberships",
              ROLE_ONLY),
          Map.entry(
              "io.opaa.space.Space#addMembership",
              "links a new membership to its space; no existing membership is written"),
          Map.entry(
              "io.opaa.space.Space#removeMembership",
              "detaches a membership that orphan removal deletes; no row is written back"),
          Map.entry("io.opaa.space.SpaceService#updateMemberRole", ROLE_ONLY));

  /**
   * Code units that build a prompt in a class without {@link
   * SecurityInvariants#PROMPT_TEXT_BLOCKS}, each with where the text it hands the model was cleaned
   * or why it holds none from outside.
   */
  static final Map<String, String> PROMPTS_PAST_THE_BLOCKS =
      Map.ofEntries(
          Map.entry(
              "io.opaa.query.answer.AnswerGenerationService#call",
              "the system text holds passages from DocumentBlock and the space instruction from"
                  + " SpaceInstructionBlock, both cleaned and fenced there"),
          Map.entry(
              "io.opaa.query.answer.AnswerGenerationService#stream",
              "the system text holds passages from DocumentBlock and the space instruction from"
                  + " SpaceInstructionBlock, both cleaned and fenced there"),
          Map.entry(
              "io.opaa.query.answer.AnswerGenerationService#streamAnswer",
              "the user message is the question QueryService#query cleaned with PromptText"),
          Map.entry(
              "io.opaa.query.toolloop.AgentToolLoop#call",
              "the system text is AgentPrompt's, which sets both instructions with"
                  + " PromptText#between; tool results are fenced by DocumentBlock"),
          Map.entry(
              "io.opaa.query.toolloop.AgentToolLoop#runAndKeep",
              "the user message is the question QueryService#query cleaned with PromptText; the"
                  + " history is the conversation window, whose entrances in"
                  + " ConversationWindowMessages clean every message"),
          Map.entry(
              "io.opaa.chat.ChatService#historyAsSpringAiMessages",
              "the persisted history enters the conversation window only through"
                  + " ConversationWindowMessages#reloaded, which cleans every message"),
          Map.entry(
              "io.opaa.retrieval.search.QueryDecompositionService#requestDecomposition",
              "question and window come as messages of DecompositionContext, cleaned there"),
          Map.entry(
              "io.opaa.retrieval.search.DecompositionContext#promptMessages",
              "question and window texts go through DecompositionContext#material, which cleans"
                  + " them with PromptText"),
          Map.entry(
              "io.opaa.metadata.ModelMetadataExtractor#askModel",
              "the prompt is ModelExtractionPrompt's, which fences title and text with"
                  + " ContentFence"));

  /** The allowlist of operations without a session and without a handler check. */
  static final String OPERATION_ALLOWLIST = "/security/anonymous-allowlist.yaml";

  @Test
  void outboundConnectionsGoThroughTheTargetCheckingBlocks() {
    assertThat(
            SecurityInvariants.unlisted(
                SecurityInvariants.connectingUnits(MainClasses.get()), CONNECTS_PAST_THE_BLOCKS))
        .as(
            "connections past RedirectFollowingFetcher and BoundedDownloader: send through them, or"
                + " name in CONNECTS_PAST_THE_BLOCKS the check the target passes before every"
                + " connection and redirect hop (ADR-0063)")
        .isEmpty();
  }

  @Test
  void storedSecretsAreDecryptedOnlyWhereTheyFollowTheirTarget() {
    assertThat(
            SecurityInvariants.unlisted(
                SecurityInvariants.decryptingUnits(MainClasses.get()), DECRYPTS_PAST_THE_BLOCKS))
        .as(
            "stored values decrypted past ModelEndpointAccess and ConnectionSecrets: use them, or"
                + " name in DECRYPTS_PAST_THE_BLOCKS what keeps the secret at the target it was"
                + " stored for")
        .isEmpty();
  }

  @Test
  void everyHandlerChecksItsCallerOrIsListed() {
    Map<String, Map<String, String>> allowlist = operationAllowlist();
    Map<String, String> listed = new LinkedHashMap<>(allowlist.get("anonymous"));
    listed.putAll(allowlist.get("withoutHandlerCheck"));

    assertThat(
            SecurityInvariants.unlisted(
                SecurityInvariants.handlersWithoutCheck(MainClasses.get()), listed))
        .as(
            "handlers without @PreAuthorize and without an @Caller parameter: add the check, or"
                + " list the operation with its reason under withoutHandlerCheck in "
                + OPERATION_ALLOWLIST)
        .isEmpty();
  }

  @Test
  void formatCompilesNoPatternPerCall() {
    assertThat(
            SecurityInvariants.unlisted(
                SecurityInvariants.perCallRegex(MainClasses.get(), "io.opaa.format"),
                PER_CALL_REGEX_IN_FORMAT))
        .as(
            "io.opaa.format compiles a pattern per call: hold it in a static final Pattern, or"
                + " name in PER_CALL_REGEX_IN_FORMAT why the call compiles nothing")
        .isEmpty();
  }

  @Test
  void securityStateIsNeverWrittenBackAsAStaleWholeRow() {
    assertThat(
            SecurityInvariants.unlisted(
                SecurityInvariants.wholeRowWriters(
                    MainClasses.get(), SECURITY_STATE_ENTITIES.keySet()),
                WHOLE_ROW_WRITERS))
        .as(
            "a loaded row with security state is changed or saved as a whole and may write back"
                + " what another transaction changed meanwhile (a role, a lock, a revocation):"
                + " write the changed column with a targeted @Modifying update, load under a row"
                + " lock, or add @DynamicUpdate and save the instance in the transaction that"
                + " loaded it; then name the code unit with its reason in WHOLE_ROW_WRITERS")
        .isEmpty();
  }

  @Test
  void foreignTextEntersAPromptOnlyThroughPromptText() {
    assertThat(
            SecurityInvariants.unlisted(
                SecurityInvariants.promptBuildingUnits(MainClasses.get()), PROMPTS_PAST_THE_BLOCKS))
        .as(
            "prompts built past PromptText and ContentFence: clean foreign text with them, or name"
                + " in PROMPTS_PAST_THE_BLOCKS where the text was cleaned or why none comes from"
                + " outside")
        .isEmpty();
  }

  @Test
  void everyPersistentTypeWithASecurityStateFieldIsClassified() {
    var classes = MainClasses.get();
    Set<String> classified = new TreeSet<>(SECURITY_STATE_ENTITIES.keySet());
    classified.addAll(NOT_SECURITY_STATE.keySet());

    assertThat(SecurityInvariants.typesWithSecurityStateFields(classes))
        .as(
            "a persistent type has a field named like security state (role, lock, revocation,"
                + " expiry ...): add it to SECURITY_STATE_ENTITIES, or to NOT_SECURITY_STATE with"
                + " what the field means instead")
        .isSubsetOf(classified);
    assertThat(SECURITY_STATE_ENTITIES.keySet())
        .doesNotContainAnyElementsOf(NOT_SECURITY_STATE.keySet());
    assertThat(classified)
        .as("classified types that are no persistent type of the backend any more")
        .allMatch(classes::contain);
  }

  @Test
  void everyExceptionStillBreaksItsRule() {
    var classes = MainClasses.get();

    assertThat(
            SecurityInvariants.stale(
                WHOLE_ROW_WRITERS.keySet(),
                SecurityInvariants.wholeRowWriters(classes, SECURITY_STATE_ENTITIES.keySet())
                    .keySet()))
        .as("WHOLE_ROW_WRITERS entries that no longer write a whole row")
        .isEmpty();
    assertThat(
            SecurityInvariants.stale(
                NOT_SECURITY_STATE.keySet(),
                SecurityInvariants.typesWithSecurityStateFields(classes)))
        .as("NOT_SECURITY_STATE entries without a field named like security state")
        .isEmpty();

    assertThat(
            SecurityInvariants.stale(
                CONNECTS_PAST_THE_BLOCKS.keySet(),
                SecurityInvariants.connectingUnits(classes).keySet()))
        .as("CONNECTS_PAST_THE_BLOCKS entries that no longer connect")
        .isEmpty();
    assertThat(
            SecurityInvariants.stale(
                DECRYPTS_PAST_THE_BLOCKS.keySet(),
                SecurityInvariants.decryptingUnits(classes).keySet()))
        .as("DECRYPTS_PAST_THE_BLOCKS entries that no longer decrypt")
        .isEmpty();
    assertThat(
            SecurityInvariants.stale(
                SecurityInvariants.SECRET_MECHANISM_CLASSES,
                SecurityInvariants.decryptingMechanismClasses(classes)))
        .as("SECRET_MECHANISM_CLASSES that no longer decrypt")
        .isEmpty();
    assertThat(
            SecurityInvariants.stale(
                operationAllowlist().get("withoutHandlerCheck").keySet(),
                SecurityInvariants.handlersWithoutCheck(classes).keySet()))
        .as("withoutHandlerCheck entries in " + OPERATION_ALLOWLIST + " whose handler checks now")
        .isEmpty();
    assertThat(
            SecurityInvariants.stale(
                PER_CALL_REGEX_IN_FORMAT.keySet(),
                SecurityInvariants.perCallRegex(classes, "io.opaa.format").keySet()))
        .as("PER_CALL_REGEX_IN_FORMAT entries that no longer call a per-call member")
        .isEmpty();
    assertThat(
            SecurityInvariants.stale(
                PROMPTS_PAST_THE_BLOCKS.keySet(),
                SecurityInvariants.promptBuildingUnits(classes).keySet()))
        .as("PROMPTS_PAST_THE_BLOCKS entries that no longer build a prompt past the blocks")
        .isEmpty();
  }

  @Test
  void noOperationIsListedBothAsAnonymousAndWithoutHandlerCheck() {
    Map<String, Map<String, String>> allowlist = operationAllowlist();

    assertThat(allowlist.get("withoutHandlerCheck").keySet())
        .doesNotContainAnyElementsOf(allowlist.get("anonymous").keySet());
  }

  /**
   * The sections {@code anonymous} and {@code withoutHandlerCheck} of {@link #OPERATION_ALLOWLIST},
   * operation to reason; an entry without operation or reason fails here.
   */
  static Map<String, Map<String, String>> operationAllowlist() {
    Object yaml;
    try (InputStream in = SecurityInvariantsTest.class.getResourceAsStream(OPERATION_ALLOWLIST)) {
      assertThat(in).as(OPERATION_ALLOWLIST).isNotNull();
      yaml = new Yaml().load(in);
    } catch (IOException e) {
      throw new IllegalStateException(OPERATION_ALLOWLIST + " is not readable", e);
    }
    assertThat(yaml).isInstanceOf(Map.class);
    Map<String, Map<String, String>> sections = new LinkedHashMap<>();
    for (String section : Set.of("anonymous", "withoutHandlerCheck")) {
      Map<String, String> entries = new LinkedHashMap<>();
      Object list = ((Map<?, ?>) yaml).get(section);
      for (Object item : list == null ? List.of() : (List<?>) list) {
        Map<?, ?> fields = (Map<?, ?>) item;
        assertThat(fields.get("operation")).as("operation of " + item).isInstanceOf(String.class);
        assertThat(fields.get("reason")).as("reason of " + item).isInstanceOf(String.class);
        entries.put((String) fields.get("operation"), (String) fields.get("reason"));
      }
      sections.put(section, entries);
    }
    return sections;
  }
}
