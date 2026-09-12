package io.opaa.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.ProviderType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalCredentialsRepository;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The account list over a mixed population: two providers (one of them managing roles through a
 * claim), an account whose provider row is gone, two local accounts in different states and a local
 * user without a credentials row.
 */
@ExtendWith(MockitoExtension.class)
class AccountAdminServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
  private static final UUID ORGANIZATION = UUID.randomUUID();

  @Mock private UserRepository users;
  @Mock private LocalCredentialsRepository credentials;
  @Mock private OidcProviderRepository providers;

  private AccountAdminService service;
  private OidcProvider directory;
  private OidcProvider partner;
  private User maria;
  private User partnerAdmin;
  private User ghost;
  private User erika;
  private User klaus;

  @BeforeEach
  void setUp() {
    service =
        new AccountAdminService(users, credentials, providers, Clock.fixed(NOW, ZoneOffset.UTC));

    // the stored issuer carries a trailing slash, the token issuer does not - normalisation joins
    // them
    directory =
        new OidcProvider(
            "Verzeichnisdienst",
            "http://idp.example/realms/opaa/",
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    directory.enable();
    partner =
        new OidcProvider(
            "Partnerportal",
            "http://idp.example/realms/partner",
            "opaa-partner",
            null,
            new OidcClaimMapping("email", "name", "realm_access.roles", "opaa-admin", null, null));
    partner.enable();
    OidcProvider local = OidcProvider.localProvider("Lokale Konten");
    when(providers.findAll()).thenReturn(List.of(directory, partner, local));

    maria = user("http://idp.example/realms/opaa", "maria.weber@stadt.example", "Maria Weber");
    partnerAdmin = user("http://idp.example/realms/partner", "p.admin@partner.example", "P. Admin");
    partnerAdmin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    ghost = user("https://gone.example", "ghost@stadt.example", "Alte Anbieterin");
    erika = local("erika@stadt.example", "Erika Muster");
    erika.setLastLoginAt(NOW.minus(Duration.ofDays(1)));
    klaus = local("klaus@stadt.example", "Klaus Weber");
    User orphan = local("orphan@stadt.example", "Ohne Zeile");

    LocalCredentials erikaRow = new LocalCredentials(erika.getId(), "Vertretung", NOW);
    erikaRow.setPasswordHash("hash", NOW);
    erikaRow.markEmailVerified(NOW);
    erikaRow.setExpiresAt(NOW.plus(Duration.ofDays(30)), NOW);
    LocalCredentials klausRow = new LocalCredentials(klaus.getId(), "Prüfung", NOW);
    klausRow.setPasswordHash("hash", NOW);
    klausRow.markEmailVerified(NOW);
    klausRow.lock(io.opaa.api.types.LockReason.ADMIN, NOW, null);

    when(users.findByOrganizationId(ORGANIZATION))
        .thenReturn(List.of(maria, partnerAdmin, ghost, erika, klaus, orphan));
    when(credentials.findAllById(anyList())).thenReturn(List.of(erikaRow, klausRow));
  }

  @Test
  void listsLocalAndProviderAccountsWithProviderStateAndRoleManagement() {
    AccountPage page = service.list(ORGANIZATION, query().build());

    assertThat(page.total()).isEqualTo(5);
    assertThat(page.items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly(
            "Alte Anbieterin", "Erika Muster", "Klaus Weber", "Maria Weber", "P. Admin");

    AccountOverview mariaRow = find(page, maria);
    assertThat(mariaRow.providerType()).isEqualTo(ProviderType.OIDC);
    assertThat(mariaRow.provider()).isSameAs(directory);
    assertThat(mariaRow.roleManagedByProvider()).isFalse();
    assertThat(mariaRow.local()).isNull();

    AccountOverview partnerRow = find(page, partnerAdmin);
    assertThat(partnerRow.provider()).isSameAs(partner);
    assertThat(partnerRow.roleManagedByProvider()).isTrue();

    AccountOverview ghostRow = find(page, ghost);
    assertThat(ghostRow.providerType()).isEqualTo(ProviderType.OIDC);
    assertThat(ghostRow.provider()).isNull();

    AccountOverview erikaRow = find(page, erika);
    assertThat(erikaRow.providerType()).isEqualTo(ProviderType.LOCAL);
    assertThat(erikaRow.local().state()).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(erikaRow.local().isInactive()).isFalse();
    assertThat(find(page, klaus).local().state()).isEqualTo(LocalAccountState.LOCKED);
  }

  @Test
  void aDisabledProviderNoLongerManagesTheRoles() {
    partner.disable();

    AccountPage page = service.list(ORGANIZATION, query().providerType(ProviderType.OIDC).build());

    assertThat(find(page, partnerAdmin).roleManagedByProvider()).isFalse();
  }

  @Test
  void providerTypeAndProviderIdNarrowTheList() {
    assertThat(service.list(ORGANIZATION, query().providerType(ProviderType.LOCAL).build()).items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly("Erika Muster", "Klaus Weber");
    assertThat(service.list(ORGANIZATION, query().providerType(ProviderType.OIDC).build()).items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly("Alte Anbieterin", "Maria Weber", "P. Admin");
    assertThat(service.list(ORGANIZATION, query().providerId(partner.getId()).build()).items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly("P. Admin");
  }

  @Test
  void theLocalOnlyFiltersNeverMatchAProviderAccount() {
    assertThat(service.list(ORGANIZATION, query().status(LocalAccountState.LOCKED).build()).items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly("Klaus Weber");
    assertThat(service.list(ORGANIZATION, query().withoutExpiry(true).build()).items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly("Klaus Weber");
    assertThat(service.list(ORGANIZATION, query().inactive(true).build()).items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly("Klaus Weber");
    assertThat(
            service
                .list(
                    ORGANIZATION,
                    query()
                        .status(LocalAccountState.ACTIVE)
                        .providerType(ProviderType.OIDC)
                        .build())
                .total())
        .isZero();
  }

  @Test
  void theSearchAndTheRoleFilterApplyToEveryAccount() {
    assertThat(service.list(ORGANIZATION, query().query("weber").build()).items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly("Klaus Weber", "Maria Weber");
    assertThat(service.list(ORGANIZATION, query().query("  ERIKA ").build()).items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly("Erika Muster");
    assertThat(service.list(ORGANIZATION, query().role(SystemRole.SYSTEM_ADMIN).build()).items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly("P. Admin");
    assertThat(
            service
                .list(ORGANIZATION, query().query("weber").role(SystemRole.SYSTEM_ADMIN).build())
                .total())
        .isZero();
  }

  @Test
  void sortsByExpiryWithAccountsWithoutOneLastAndByEmail() {
    AccountPage byExpiry =
        service.list(ORGANIZATION, query().sort(AccountQuery.Sort.EXPIRES_AT).build());
    assertThat(byExpiry.items().get(0).user()).isSameAs(erika);

    AccountPage byEmailDescending =
        service.list(ORGANIZATION, query().sort(AccountQuery.Sort.EMAIL).descending(true).build());
    assertThat(byEmailDescending.items())
        .extracting(a -> a.user().getEmail())
        .containsExactly(
            "p.admin@partner.example",
            "maria.weber@stadt.example",
            "klaus@stadt.example",
            "ghost@stadt.example",
            "erika@stadt.example");
  }

  @Test
  void sortsByOriginWithLocalAccountsFirstAndAnUnknownIssuerLast() {
    AccountPage page = service.list(ORGANIZATION, query().sort(AccountQuery.Sort.ORIGIN).build());

    assertThat(page.items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly(
            // die beiden lokalen zuerst (untereinander nach ihrer stabilen Zweitordnung),
            // dann die Anbieter nach Namen, zuletzt das Konto ohne Anbieterzeile
            "Erika Muster", "Klaus Weber", "P. Admin", "Maria Weber", "Alte Anbieterin");
    assertThat(page.items().get(0).isLocal()).isTrue();
    assertThat(page.items().get(1).isLocal()).isTrue();
    assertThat(page.items().get(2).provider()).isSameAs(partner);
    assertThat(page.items().get(3).provider()).isSameAs(directory);
    assertThat(page.items().get(4).provider()).isNull();
  }

  @Test
  void sortsByRoleByPrivilegeNotByTheEnumsOwnOrder() {
    AccountPage page = service.list(ORGANIZATION, query().sort(AccountQuery.Sort.ROLE).build());

    assertThat(page.items())
        .extracting(a -> a.user().getSystemRole())
        .containsExactly(
            SystemRole.USER,
            SystemRole.USER,
            SystemRole.USER,
            SystemRole.USER,
            SystemRole.SYSTEM_ADMIN);
    // absteigend beginnt bei der Systemverwaltung - der Wert, den eine Prüfung zuerst sucht
    AccountPage descending =
        service.list(ORGANIZATION, query().sort(AccountQuery.Sort.ROLE).descending(true).build());
    assertThat(descending.items().get(0).user().getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
  }

  @Test
  void sortsByStateWithWhatNeedsAttentionFirstAndProviderAccountsLast() {
    AccountPage page = service.list(ORGANIZATION, query().sort(AccountQuery.Sort.STATUS).build());

    assertThat(page.items().get(0).local().state()).isEqualTo(LocalAccountState.LOCKED);
    assertThat(page.items().get(1).local().state()).isEqualTo(LocalAccountState.ACTIVE);
    // die drei Anbieterkonten tragen keinen Zustand aus OPAAs Hand und stehen dahinter
    assertThat(page.items().subList(2, 5)).allMatch(a -> a.local() == null);
  }

  @Test
  void pagesTheMatchesAndReportsTheTotal() {
    AccountPage second = service.list(ORGANIZATION, query().page(1).size(2).build());
    assertThat(second.items())
        .extracting(a -> a.user().getDisplayName())
        .containsExactly("Klaus Weber", "Maria Weber");
    assertThat(second.total()).isEqualTo(5);
    assertThat(second.page()).isEqualTo(1);
    assertThat(second.size()).isEqualTo(2);

    AccountPage beyond = service.list(ORGANIZATION, query().page(9).size(2).build());
    assertThat(beyond.items()).isEmpty();
    assertThat(beyond.total()).isEqualTo(5);
  }

  private static AccountOverview find(AccountPage page, User user) {
    return page.items().stream().filter(a -> a.user() == user).findFirst().orElseThrow();
  }

  private static User user(String issuer, String email, String displayName) {
    User user = new User(UUID.randomUUID().toString(), issuer, email, displayName);
    user.setOrganizationId(ORGANIZATION);
    setId(user, UUID.randomUUID());
    return user;
  }

  private static User local(String email, String displayName) {
    User user = User.localAccount(email, displayName);
    user.setOrganizationId(ORGANIZATION);
    user.setLastLoginAt(null);
    return user;
  }

  private static void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static QueryBuilder query() {
    return new QueryBuilder();
  }

  /** Readable construction of the eleven-field record with the defaults of the endpoint. */
  private static final class QueryBuilder {
    private String query;
    private ProviderType providerType;
    private UUID providerId;
    private SystemRole role;
    private LocalAccountState status;
    private boolean withoutExpiry;
    private boolean inactive;
    private AccountQuery.Sort sort = AccountQuery.Sort.DISPLAY_NAME;
    private boolean descending;
    private int page;
    private int size = AccountQuery.DEFAULT_PAGE_SIZE;

    QueryBuilder query(String value) {
      this.query = value;
      return this;
    }

    QueryBuilder providerType(ProviderType value) {
      this.providerType = value;
      return this;
    }

    QueryBuilder providerId(UUID value) {
      this.providerId = value;
      return this;
    }

    QueryBuilder role(SystemRole value) {
      this.role = value;
      return this;
    }

    QueryBuilder status(LocalAccountState value) {
      this.status = value;
      return this;
    }

    QueryBuilder withoutExpiry(boolean value) {
      this.withoutExpiry = value;
      return this;
    }

    QueryBuilder inactive(boolean value) {
      this.inactive = value;
      return this;
    }

    QueryBuilder sort(AccountQuery.Sort value) {
      this.sort = value;
      return this;
    }

    QueryBuilder descending(boolean value) {
      this.descending = value;
      return this;
    }

    QueryBuilder page(int value) {
      this.page = value;
      return this;
    }

    QueryBuilder size(int value) {
      this.size = value;
      return this;
    }

    AccountQuery build() {
      return new AccountQuery(
          query,
          providerType,
          providerId,
          role,
          status,
          withoutExpiry,
          inactive,
          sort,
          descending,
          page,
          size);
    }
  }
}
