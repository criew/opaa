package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.AccountPageResponse;
import io.opaa.api.dto.AccountResponse;
import io.opaa.api.types.LocalAccountActivity;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.ProviderType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.account.AccountOverview;
import io.opaa.auth.account.AccountPage;
import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalUserOverview;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Secures the field assignment of the account list, local and provider rows alike (AGENTS.md). */
class AccountResponseMapperTest {

  private static final Instant CREATED = Instant.parse("2026-09-11T08:00:00Z");

  @Test
  void mapsAProviderAccountWithItsProvider() {
    OidcProvider provider =
        new OidcProvider(
            "Partnerportal",
            "http://idp.example/realms/partner",
            "opaa-partner",
            null,
            new OidcClaimMapping("email", "name", "realm_access.roles", "opaa-admin", null, null));
    provider.enable();
    UUID id = UUID.randomUUID();
    User user =
        new User(
            "subject-1",
            "http://idp.example/realms/partner",
            "p.admin@partner.example",
            "P. Admin");
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    setId(user, id);

    AccountResponse response =
        AccountResponseMapper.toResponse(new AccountOverview(user, provider, null, true));

    assertThat(response.getId()).isEqualTo(id);
    assertThat(response.getEmail()).isEqualTo("p.admin@partner.example");
    assertThat(response.getDisplayName()).isEqualTo("P. Admin");
    assertThat(response.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThat(response.getProviderType()).isEqualTo(ProviderType.OIDC);
    assertThat(response.getIssuer()).isEqualTo("http://idp.example/realms/partner");
    assertThat(response.getRoleManagedByProvider()).isTrue();
    assertThat(response.getCreatedAt()).isEqualTo(user.getCreatedAt());
    assertThat(response.getProvider()).isNotNull();
    assertThat(response.getProvider().getId()).isEqualTo(provider.getId());
    assertThat(response.getProvider().getDisplayName()).isEqualTo("Partnerportal");
    assertThat(response.getProvider().getEnabled()).isTrue();
    assertThat(response.getLocal()).isNull();
    assertThat(response.toString()).doesNotContain("lastLogin");
  }

  @Test
  void keepsTheIssuerOfAnAccountWhoseProviderIsGone() {
    User user = new User("subject-2", "https://gone.example/", "ghost@stadt.example", "Ghost");
    setId(user, UUID.randomUUID());

    AccountResponse response =
        AccountResponseMapper.toResponse(new AccountOverview(user, null, null, false));

    assertThat(response.getProviderType()).isEqualTo(ProviderType.OIDC);
    assertThat(response.getIssuer()).isEqualTo("https://gone.example/");
    assertThat(response.getProvider()).isNull();
    assertThat(response.getLocal()).isNull();
    assertThat(response.getRoleManagedByProvider()).isFalse();
  }

  @Test
  void mapsALocalAccountThroughTheLocalUserResponse() {
    UUID id = UUID.randomUUID();
    User user = User.localAccount("erika@stadt.example", "Erika Muster");
    setId(user, id);
    // Die Nutzerzeile entsteht mit `Instant.now()`, die Zugangsdatenzeile mit CREATED - genau
    // deshalb trennt die Zusicherung unten die beiden Quellen wirklich.
    LocalCredentials row = new LocalCredentials(id, "Projekt Bauamt", CREATED);
    row.setPasswordHash("hash", CREATED);
    row.markEmailVerified(CREATED);
    LocalUserOverview local =
        new LocalUserOverview(user, row, LocalAccountState.ACTIVE, LocalAccountActivity.NEVER);

    AccountResponse response =
        AccountResponseMapper.toResponse(new AccountOverview(user, null, local, false));

    assertThat(response.getProviderType()).isEqualTo(ProviderType.LOCAL);
    assertThat(response.getIssuer()).isEqualTo(LocalIssuer.URN);
    assertThat(response.getProvider()).isNull();
    assertThat(response.getRoleManagedByProvider()).isFalse();
    assertThat(response.getLocal()).isNotNull();
    assertThat(response.getLocal().getId()).isEqualTo(id);
    assertThat(response.getLocal().getStatus()).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(response.getLocal().getActivity()).isEqualTo(LocalAccountActivity.NEVER);
    assertThat(response.getLocal().getCreatedReason()).isEqualTo("Projekt Bauamt");
    assertThat(response.getLocal().getCreatedAt()).isEqualTo(CREATED);
    // „Angelegt" kommt für ein lokales Konto aus der Zugangsdatenzeile, nicht aus der Nutzerzeile
    // - dieselbe Quelle, aus der die Schwesterliste ihre Spalte füllt. Ohne diese Zeile fiele ein
    // Rückfall auf user.getCreatedAt() keinem Test auf.
    assertThat(response.getCreatedAt()).isEqualTo(CREATED);
    assertThat(response.getCreatedAt()).isNotEqualTo(user.getCreatedAt());
  }

  @Test
  void mapsThePageWithItsTotal() {
    User user = new User("subject-3", "test-issuer", "a@example.com", "A");
    setId(user, UUID.randomUUID());
    AccountOverview account = new AccountOverview(user, null, null, false);

    AccountPageResponse page =
        AccountResponseMapper.toPage(new AccountPage(List.of(account), 7, 2, 1));

    assertThat(page.getItems()).hasSize(1);
    assertThat(page.getItems().get(0).getEmail()).isEqualTo("a@example.com");
    assertThat(page.getTotal()).isEqualTo(7);
    assertThat(page.getPage()).isEqualTo(2);
    assertThat(page.getSize()).isEqualTo(1);
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
}
