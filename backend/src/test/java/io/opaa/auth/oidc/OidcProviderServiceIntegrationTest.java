package io.opaa.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.test.OpaaIntegrationTest;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * {@link OidcProviderService} and {@link OidcProviderRegistry} against a real Postgres with the
 * versioned schema (#1329, ADR-0025): the two schema invariants ({@code
 * uq_oidc_providers_issuer_uri}, {@code ux_oidc_providers_single_default}) hold under the service,
 * every change writes an audit row, and - the acceptance criterion of #1329 - a provider created
 * through the service is able to authenticate a token signed with its own key <em>without a
 * restart</em>: the registry rebuilt itself after the commit, and a provider disabled afterwards is
 * refused on the next token.
 */
@OpaaIntegrationTest
class OidcProviderServiceIntegrationTest {

  /**
   * The path segment that makes an issuer of this class recognisable - see removeOwnProviders().
   */
  private static final String OWN_ISSUER_PREFIX = "https://idp.example/oidc-provider-it/";

  @Autowired private OidcProviderService service;
  @Autowired private OidcProviderRegistry registry;
  @Autowired private OidcProviderRepository repository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupSubjectDirectory groupDirectory;
  @Autowired private UserRepository userRepository;
  @Autowired private AuthProperties authProperties;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID userId;
  private HttpServer jwks;
  private RSAKey key;
  private String issuer;

  @BeforeEach
  void setUp() throws Exception {
    removeOwnProviders();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "OIDC Test Org")).getId();
    // a login-capable administrator of this organization that belongs to none of the providers
    // under test (the dev issuer counts in this dev context): disabling or deleting an enabled
    // provider is guarded by LocalAdminAvailabilityGuard (ADR-0033, Entscheidung 4)
    User user =
        new User(
            UUID.randomUUID().toString(),
            authProperties.dev().issuer(),
            "oidc@example.com",
            "Test");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    userId = userRepository.save(user).getId();

    key = new RSAKeyGenerator(2048).keyID("k1").generate();
    String jwksBody = new JWKSet(key.toPublicJWK()).toString();
    jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwks.createContext(
        "/certs",
        exchange -> {
          byte[] bytes = jwksBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    jwks.start();
    // the issuer is never fetched here: the JWK set override is the only address the registry
    // reads for this provider, exactly the Compose split ADR-0025 describes. The path segment is
    // this class's own - see removeOwnProviders().
    issuer = OWN_ISSUER_PREFIX + UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    jwks.stop(0);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM diagnostic_impersonation_grants WHERE organization_id = ?", organizationId);
    removeOwnProviders();
    jdbcTemplate.update("DELETE FROM groups WHERE organization_id = ?", organizationId);
    registry.refresh();
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  /**
   * Only the providers of this class: every issuer it registers starts with {@link
   * #OWN_ISSUER_PREFIX} or addresses its own local discovery server. The host alone would not do -
   * {@code UserServiceMultiProviderIntegrationTest} registers under {@code
   * https://idp.example/realms/...} as well, and this table is shared like every other.
   */
  private void removeOwnProviders() {
    // fk_groups_provider is RESTRICT and a group's grants are RESTRICT in turn (#1812): the three
    // deletions have to run from the inside out, or the provider row cannot go at all.
    String ownProviders =
        "SELECT id FROM oidc_providers WHERE issuer_uri LIKE ? OR issuer_uri LIKE ?";
    jdbcTemplate.update(
        "DELETE FROM asset_grants WHERE subject_group_id IN"
            + " (SELECT id FROM groups WHERE provider_id IN ("
            + ownProviders
            + "))",
        OWN_ISSUER_PREFIX + "%",
        "http://127.0.0.1:%");
    jdbcTemplate.update(
        "DELETE FROM groups WHERE provider_id IN (" + ownProviders + ")",
        OWN_ISSUER_PREFIX + "%",
        "http://127.0.0.1:%");
    jdbcTemplate.update(
        "DELETE FROM oidc_providers WHERE issuer_uri LIKE ? OR issuer_uri LIKE ?",
        OWN_ISSUER_PREFIX + "%",
        "http://127.0.0.1:%");
  }

  /** A group of {@code provider}, the way the token synchronisation would create it. */
  private Group providerGroup(OidcProvider provider, String name) {
    return groupRepository.save(
        new Group(
            organizationId,
            GroupKind.IDENTITY_PROVIDER,
            name,
            null,
            provider.getId(),
            name,
            null,
            null));
  }

  /**
   * A grant of {@code group} on an arbitrary asset - {@code asset_grants.asset_id} carries no
   * foreign key since #1811, so no library row is needed to make the group "wirksam".
   */
  private void grantOnSomeAsset(Group group) {
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_group_id, role) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'GROUP', ?,"
            + " 'VIEWER')",
        UUID.randomUUID(),
        UUID.randomUUID(),
        organizationId,
        group.getId());
  }

  /**
   * A still-conferring diagnostic authorisation (ADR-0016) scoped to {@code group} - written
   * directly, because the service that issues one demands a holder that is an auditor.
   */
  private void diagnosticAuthorizationScopedTo(Group group) {
    jdbcTemplate.update(
        "INSERT INTO diagnostic_impersonation_grants (id, organization_id, holder_user_id,"
            + " scope_group_id, valid_from, valid_until, granted_by_user_id, granted_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        organizationId,
        userId,
        group.getId(),
        Timestamp.from(Instant.now().minusSeconds(60)),
        Timestamp.from(Instant.now().plusSeconds(3600)),
        userId,
        Timestamp.from(Instant.now().minusSeconds(60)));
  }

  /** A second provider besides the default one, which may be deleted and disabled. */
  private OidcProvider secondProvider() {
    service.createProvider(organizationId, userId, draft("Erster", issuer));
    return service.createProvider(organizationId, userId, draft("Partner", issuer + "-2"));
  }

  /** The groups of a provider go with it as long as none of them carries a right (ADR-0036/2). */
  @Test
  void deletingAProviderTakesItsEffectFreeGroupsWithIt() {
    OidcProvider partner = secondProvider();
    Group group = providerGroup(partner, "Fachbereich 3");

    service.deleteProvider(organizationId, userId, partner.getId());

    assertThat(groupRepository.findById(group.getId())).isEmpty();
    assertThat(repository.findById(partner.getId())).isEmpty();
  }

  /**
   * The counterpart: until the transfer operation exists there is no way past this 409 other than
   * removing the rights - the message has to say how much work that is.
   */
  @Test
  void aProviderWhoseGroupStillCarriesARightIsRefusedWithTheCounts() {
    OidcProvider partner = secondProvider();
    Group group = providerGroup(partner, "Fachbereich 3");
    grantOnSomeAsset(group);

    assertThatThrownBy(() -> service.deleteProvider(organizationId, userId, partner.getId()))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            thrown ->
                assertThat(((ConflictException) thrown).getCode())
                    .isEqualTo(OidcProviderService.PROVIDER_GROUPS_IN_EFFECT))
        .hasMessageContaining("1 Gruppe wirkt noch")
        .hasMessageContaining("1 Berechtigung")
        .hasMessageContaining("1 Objekt");
    assertThat(repository.findById(partner.getId())).isPresent();
    assertThat(groupRepository.findById(group.getId())).isPresent();
  }

  /**
   * {@code fk_diagnostic_impersonation_grants_scope_organization} is {@code ON DELETE CASCADE}
   * (changeset 003): without counting the authorisation as an effect, deleting the provider would
   * take a valid Diagnose-Vollmacht with it, silently and without the {@code
   * DIAGNOSTIC_IMPERSONATION_REVOKED} event ADR-0016 requires.
   */
  @Test
  void aProviderWhoseGroupIsTheScopeOfADiagnosticAuthorizationIsRefused() {
    OidcProvider partner = secondProvider();
    Group group = providerGroup(partner, "Fachbereich 3");
    diagnosticAuthorizationScopedTo(group);

    assertThatThrownBy(() -> service.deleteProvider(organizationId, userId, partner.getId()))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            thrown ->
                assertThat(((ConflictException) thrown).getCode())
                    .isEqualTo(OidcProviderService.PROVIDER_GROUPS_IN_EFFECT))
        .hasMessageContaining("1 Gruppe wirkt noch")
        .hasMessageContaining("Geltungsbereich von 1 Diagnose-Vollmacht");
    assertThat(repository.findById(partner.getId())).isPresent();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM diagnostic_impersonation_grants WHERE scope_group_id = ?",
                Integer.class,
                group.getId()))
        .isEqualTo(1);
  }

  /**
   * The counterpart: a revoked authorisation already carries its revocation event and confers
   * nothing, so it must not make the provider undeletable for good - there is no operation that
   * removes such a row.
   */
  @Test
  void aRevokedDiagnosticAuthorizationDoesNotHoldTheProviderBack() {
    OidcProvider partner = secondProvider();
    Group group = providerGroup(partner, "Fachbereich 3");
    diagnosticAuthorizationScopedTo(group);
    jdbcTemplate.update(
        "UPDATE diagnostic_impersonation_grants SET revoked_at = ?, revoked_by_user_id = ?"
            + " WHERE scope_group_id = ?",
        Timestamp.from(Instant.now()),
        userId,
        group.getId());

    service.deleteProvider(organizationId, userId, partner.getId());

    assertThat(repository.findById(partner.getId())).isEmpty();
    assertThat(groupRepository.findById(group.getId())).isEmpty();
  }

  /**
   * Disabling leaves group, membership and grant untouched, but the group stops being an effective
   * one: no new grant may target it while the provider is off (ADR-0036, Entscheidung 2).
   */
  @Test
  void theGroupsOfADisabledProviderStopBeingEffectiveWithoutLosingAnything() {
    OidcProvider partner = secondProvider();
    Group group = providerGroup(partner, "Fachbereich 3");
    grantOnSomeAsset(group);
    assertThat(groupDirectory.find(group.getId()).orElseThrow().providerDisabled()).isFalse();

    service.setEnabled(organizationId, userId, partner.getId(), false);

    assertThat(groupDirectory.find(group.getId()).orElseThrow().providerDisabled()).isTrue();
    assertThat(groupRepository.findById(group.getId())).isPresent();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM asset_grants WHERE subject_group_id = ?",
                Integer.class,
                group.getId()))
        .isEqualTo(1);
  }

  /**
   * Vorgabe of ADR-0036/2: the default provider is this installation's own, every further one is
   * another house's until the system administration says otherwise.
   */
  @Test
  void theDefaultProviderIsNotExternalAndEveryFurtherOneIs() {
    OidcProvider first = service.createProvider(organizationId, userId, draft("Erster", issuer));
    OidcProvider partner =
        service.createProvider(organizationId, userId, draft("Partner", issuer + "-2"));

    assertThat(first.isDefaultProvider()).isTrue();
    assertThat(first.isExternal()).isFalse();
    assertThat(partner.isExternal()).isTrue();
  }

  @Test
  void theExternalMarkIsChangedAndAudited() {
    OidcProvider partner = secondProvider();

    OidcProvider updated = service.setExternal(organizationId, userId, partner.getId(), false);

    assertThat(updated.isExternal()).isFalse();
    assertThat(repository.findById(partner.getId()).orElseThrow().isExternal()).isFalse();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT after FROM audit_log WHERE organization_id = ? AND event_type ="
                    + " 'OIDC_PROVIDER_CHANGED' AND object_id = ?",
                organizationId,
                partner.getId().toString()))
        .anySatisfy(
            row -> assertThat(row.get("after").toString()).contains("\"isExternal\":false"));
  }

  private OidcProviderDraft draft(String name, String issuerUri) {
    return new OidcProviderDraft(
        name, issuerUri, "opaa-frontend", jwksUri(), OidcClaimMapping.keycloakDefaults());
  }

  private String jwksUri() {
    return "http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs";
  }

  /** Serves a discovery document for {@code discoveredIssuer} on the local server. */
  private void serveDiscoveryFor(String discoveredIssuer) {
    String path = discoveredIssuer.substring(discoveredIssuer.indexOf('/', "http://".length()));
    jwks.createContext(
        path + "/.well-known/openid-configuration",
        exchange -> {
          byte[] bytes =
              ("{\"issuer\":\"" + discoveredIssuer + "\",\"jwks_uri\":\"" + jwksUri() + "\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
  }

  @Test
  void aProviderCreatedThroughTheServiceAuthenticatesTokensWithoutARestart() throws Exception {
    service.createProvider(organizationId, userId, draft("Verzeichnisdienst", issuer));

    assertThat(registry.findEnabledByIssuer(issuer))
        .as("registry rebuilt after commit")
        .isPresent();
    AuthenticationManager manager = registry.resolve(issuer);
    Authentication authenticated =
        manager.authenticate(new BearerTokenAuthenticationToken(signedToken(issuer)));
    assertThat(authenticated).isInstanceOf(JwtAuthenticationToken.class);
    assertThat(((JwtAuthenticationToken) authenticated).getToken().getSubject()).isEqualTo("alice");
  }

  @Test
  void aDisabledProviderIsRefusedOnTheNextToken() {
    OidcProvider first = service.createProvider(organizationId, userId, draft("Erster", issuer));
    String second = issuer + "-2";
    OidcProvider partner = service.createProvider(organizationId, userId, draft("Partner", second));
    assertThat(registry.findEnabledByIssuer(second)).isPresent();

    service.setEnabled(organizationId, userId, partner.getId(), false);

    assertThat(registry.findEnabledByIssuer(second)).isEmpty();
    assertThatThrownBy(
            () ->
                registry
                    .resolve(second)
                    .authenticate(new BearerTokenAuthenticationToken(signedToken(second))))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining(OidcProviderRegistry.UNKNOWN_ISSUER);
    assertThat(registry.findEnabledByIssuer(first.getIssuerUri())).isPresent();
  }

  @Test
  void theSchemaKeepsTheIssuerUniqueAndTheDefaultSingular() {
    service.createProvider(organizationId, userId, draft("Erster", issuer));

    assertThatThrownBy(() -> service.createProvider(organizationId, userId, draft("Kopie", issuer)))
        .isInstanceOf(ConflictException.class);
    OidcProvider partner =
        service.createProvider(organizationId, userId, draft("Partner", issuer + "-2"));
    service.makeDefault(organizationId, userId, partner.getId());

    List<OidcProvider> own =
        repository.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
            .filter(provider -> provider.getIssuerUri().startsWith(OWN_ISSUER_PREFIX))
            .toList();
    assertThat(own).filteredOn(OidcProvider::isDefaultProvider).singleElement().isEqualTo(partner);
  }

  @Test
  void aProviderWithoutAJwkSetOverrideIsBuiltFromItsDiscoveryDocument() throws Exception {
    String discovered =
        "http://127.0.0.1:" + jwks.getAddress().getPort() + "/realms/" + UUID.randomUUID();
    serveDiscoveryFor(discovered);

    OidcProvider created =
        service.createProvider(
            organizationId,
            userId,
            new OidcProviderDraft(
                "Entdeckt",
                discovered,
                "opaa-frontend",
                null,
                OidcClaimMapping.keycloakDefaults()));

    assertThat(registry.healthOf(created.getId()).ready()).isTrue();
    Authentication authenticated =
        registry
            .resolve(discovered)
            .authenticate(new BearerTokenAuthenticationToken(signedToken(discovered)));
    assertThat(authenticated).isInstanceOf(JwtAuthenticationToken.class);
  }

  @Test
  void theDefaultProviderCanNeitherBeDisabledNorDeletedUntilAnotherOneTookItsPlace() {
    OidcProvider standard = service.createProvider(organizationId, userId, draft("Erster", issuer));
    OidcProvider partner =
        service.createProvider(organizationId, userId, draft("Partner", issuer + "-2"));

    assertThatThrownBy(() -> service.setEnabled(organizationId, userId, standard.getId(), false))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> service.deleteProvider(organizationId, userId, standard.getId()))
        .isInstanceOf(ConflictException.class);
    assertThat(repository.findById(standard.getId())).map(OidcProvider::isEnabled).contains(true);

    service.makeDefault(organizationId, userId, partner.getId());
    service.setEnabled(organizationId, userId, standard.getId(), false);
    service.deleteProvider(organizationId, userId, standard.getId());
    assertThat(repository.findById(standard.getId())).isEmpty();
    assertThat(registry.findEnabledByIssuer(issuer)).isEmpty();
  }

  /**
   * Regression guard for #1832: switching off the last enabled provider leaves {@code is_default}
   * on its row (ADR-0033, Entscheidung 4), so "the enabled default" has to be asked for
   * separately.
   */
  @Test
  void theLastProviderSwitchedOffKeepsIsDefaultButIsNoLongerTheEnabledDefault() {
    OidcProvider standard = service.createProvider(organizationId, userId, draft("Erster", issuer));
    assertThat(repository.findByDefaultProviderTrueAndEnabledTrue())
        .map(OidcProvider::getId)
        .contains(standard.getId());

    service.setEnabled(organizationId, userId, standard.getId(), false, true);

    assertThat(repository.findByDefaultProviderTrue())
        .map(OidcProvider::getId)
        .contains(standard.getId());
    assertThat(repository.findByDefaultProviderTrueAndEnabledTrue())
        .map(OidcProvider::getId)
        .isNotEqualTo(Optional.of(standard.getId()));
  }

  /**
   * The rule with irreversible data effect, against real rows: {@code users.issuer} holds the
   * token's {@code iss} exactly as minted (here with a trailing slash), and the provider's stored
   * issuer must count those rows - a normalized comparison would find none and let the change
   * through.
   */
  @Test
  void changingTheIssuerOfAProviderThatMintedAccountsIsRefusedAgainstRealAccountRows() {
    String slashed = issuer + "/";
    User minted = new User(UUID.randomUUID().toString(), slashed, "minted@example.com", "Minted");
    minted.setOrganizationId(organizationId);
    UUID mintedId = userRepository.save(minted).getId();
    try {
      OidcProvider provider =
          service.createProvider(organizationId, userId, draft("Auth0", slashed));

      assertThatThrownBy(
              () ->
                  service.updateProvider(
                      organizationId, userId, provider.getId(), draft("Auth0", issuer + "-neu")))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("1 Konten");
      assertThat(repository.findById(provider.getId()))
          .map(OidcProvider::getIssuerUri)
          .as("stored as minted, slash included")
          .contains(slashed);
    } finally {
      userRepository.deleteById(mintedId);
    }
  }

  @Test
  void aSecondProviderWhoseIssuerDiffersOnlyByATrailingSlashIsRefused() {
    service.createProvider(organizationId, userId, draft("Erster", issuer));

    assertThatThrownBy(
            () -> service.createProvider(organizationId, userId, draft("Kopie", issuer + "/")))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void everyChangeLeavesOneAuditRowOfItsOwnType() {
    OidcProvider created = service.createProvider(organizationId, userId, draft("Erster", issuer));
    service.updateProvider(organizationId, userId, created.getId(), draft("Umbenannt", issuer));
    OidcProvider partner =
        service.createProvider(organizationId, userId, draft("Partner", issuer + "-2"));
    service.setEnabled(organizationId, userId, partner.getId(), false);
    service.setEnabled(organizationId, userId, partner.getId(), true);
    service.deleteProvider(organizationId, userId, partner.getId());

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT event_type FROM audit_log WHERE organization_id = ? ORDER BY recorded_at, event_id",
            organizationId);
    assertThat(rows)
        .extracting(row -> row.get("event_type"))
        .containsExactly(
            "OIDC_PROVIDER_CREATED",
            "OIDC_PROVIDER_CHANGED",
            "OIDC_PROVIDER_CREATED",
            "OIDC_PROVIDER_DISABLED",
            "OIDC_PROVIDER_ENABLED",
            "OIDC_PROVIDER_DELETED");
  }

  private String signedToken(String tokenIssuer) throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(tokenIssuer)
            .subject("alice")
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("email", "alice@example.com")
            .build();
    SignedJWT jwt =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
