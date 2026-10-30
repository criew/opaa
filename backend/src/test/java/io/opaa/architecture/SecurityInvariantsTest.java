package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The security invariants of {@link SecurityInvariants} over the backend, each with its allowlist
 * of justified exceptions; an entry the code outgrew fails as well, so every list only shrinks.
 * {@link SecurityInvariantsFixtureTest} proves that each rule fires.
 */
class SecurityInvariantsTest {

  private static final String MODEL_ADDRESS_CHECKED =
      "ModelEndpointAccess or the administration checks the address with ModelAddressPolicy before"
          + " it is stored or used; follows no redirect";

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
          Map.entry("io.opaa.llm.HttpEmbeddingProbe#exchange", MODEL_ADDRESS_CHECKED),
          Map.entry("io.opaa.llm.LlmModelConnectionTester#probe", MODEL_ADDRESS_CHECKED),
          Map.entry("io.opaa.llm.LlmModelDiscoverer#send", MODEL_ADDRESS_CHECKED),
          Map.entry(
              "io.opaa.llm.ModelRedirectRefusal#intercept",
              "re-issues the model client's own request with redirects off; the endpoint was"
                  + " checked with ModelAddressPolicy when it was stored"),
          Map.entry("io.opaa.llm.RerankClient#send", MODEL_ADDRESS_CHECKED),
          Map.entry("io.opaa.llm.TranscriptionClient#send", MODEL_ADDRESS_CHECKED),
          Map.entry(
              "io.opaa.audit.SyslogSender#deliver",
              "the SIEM receiver comes from the operator's configuration (opaa.siem-export.*),"
                  + " never from a request"),
          Map.entry(
              "io.opaa.audit.SyslogSender#secured",
              "wraps the socket deliver connected to the configured SIEM receiver in TLS"),
          Map.entry(
              "io.opaa.knowledge.ClamdClient#scan",
              "the virus scanner comes from the operator's configuration (opaa.malware-scan.*),"
                  + " never from a request"),
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
          Map.entry("io.opaa.installation.StepChecks#chatModel", WIZARD_STEP_KEY),
          Map.entry("io.opaa.installation.StepChecks#rerankModel", WIZARD_STEP_KEY),
          Map.entry("io.opaa.installation.StepChecks#transcriptionModel", WIZARD_STEP_KEY),
          Map.entry(
              "io.opaa.installation.StepRules#apiKey",
              "turns a saved wizard step, address and key stored together, into the settings the"
                  + " installation writes"),
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
              "io.opaa.llm.EmbeddingModelAdministration#decrypt",
              "reuses the installed model's key for a draft only at the stored address"
                  + " (ModelEndpointRules#sameEndpoint)"),
          Map.entry("io.opaa.llm.InstalledEmbeddingModel#build", OWN_ADDRESS_AND_KEY),
          Map.entry(
              "io.opaa.llm.ModelRoleAdministration#testKey",
              "reuses a role's stored key for a test only at the stored address"
                  + " (ModelEndpointRules#sameEndpoint)"),
          Map.entry(
              "io.opaa.llm.ModelRoleEndpoints#decrypt",
              "loads the rerank and transcription endpoints with their own address and key"),
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
  void everyExceptionStillBreaksItsRule() {
    var classes = MainClasses.get();

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
