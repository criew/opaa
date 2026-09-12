package io.opaa.api;

import io.opaa.api.dto.AccountPageResponse;
import io.opaa.api.dto.AccountProviderResponse;
import io.opaa.api.dto.AccountResponse;
import io.opaa.auth.account.AccountOverview;
import io.opaa.auth.account.AccountPage;
import io.opaa.auth.oidc.OidcProvider;

/**
 * Entity to response mapping of the account list (#1601). A local account carries the very {@link
 * io.opaa.api.dto.LocalUserResponse} of the local account management, mapped by {@link
 * LocalUserResponseMapper}, so that the two lists never disagree about a local account; the
 * activity timestamp itself has no field to land in.
 */
final class AccountResponseMapper {

  private AccountResponseMapper() {}

  static AccountResponse toResponse(AccountOverview account) {
    AccountResponse response =
        new AccountResponse(
            account.user().getId(),
            account.user().getEmail(),
            account.user().getDisplayName(),
            account.user().getSystemRole(),
            account.providerType(),
            account.user().getIssuer(),
            account.roleManagedByProvider(),
            // Für ein lokales Konto die Zeile der Zugangsdaten, wie die Schwesterliste sie nennt:
            // Beide entstehen heute in derselben Transaktion, laufen aber mit der Übergabe aus
            // Entscheidung 12 auseinander - dann zeigten zwei Listen zwei Daten für dasselbe Konto.
            account.local() == null
                ? account.user().getCreatedAt()
                : account.local().credentials().getCreatedAt());
    OidcProvider provider = account.provider();
    if (provider != null) {
      response.setProvider(
          new AccountProviderResponse(
              provider.getId(), provider.getDisplayName(), provider.isEnabled()));
    }
    if (account.local() != null) {
      response.setLocal(LocalUserResponseMapper.toResponse(account.local()));
    }
    return response;
  }

  static AccountPageResponse toPage(AccountPage page) {
    return new AccountPageResponse(
        page.items().stream().map(AccountResponseMapper::toResponse).toList(),
        page.total(),
        page.page(),
        page.size());
  }
}
