package io.opaa.connection.web;

import io.opaa.api.dto.ConnectableProfile;
import io.opaa.api.dto.ConnectedAccount;
import io.opaa.api.dto.ConnectedAccountLibrary;
import io.opaa.api.dto.ConnectedAccountsOverview;
import io.opaa.api.dto.MissingAccess;
import io.opaa.connection.account.AccountOverview;

/** Maps a person's own connected accounts onto their generated responses; no secret has a field. */
final class ConnectedAccountResponseMapper {

  private ConnectedAccountResponseMapper() {}

  static ConnectedAccountsOverview toResponse(AccountOverview overview) {
    return new ConnectedAccountsOverview()
        .accounts(
            overview.accounts().stream().map(ConnectedAccountResponseMapper::toResponse).toList())
        .connectable(
            overview.connectable().stream()
                .map(
                    profile ->
                        new ConnectableProfile()
                            .profileId(profile.profileId())
                            .name(profile.name())
                            .sourceType(profile.sourceType().key())
                            .authMethod(profile.authMethod())
                            .secretForm(profile.secretForm()))
                .toList())
        .missingAccess(
            new MissingAccess()
                .responsible(overview.missingAccess().responsible())
                .text(overview.missingAccess().text()));
  }

  static ConnectedAccount toResponse(AccountOverview.Account account) {
    return new ConnectedAccount()
        .profileId(account.profileId())
        .profileName(account.profileName())
        .sourceType(account.sourceType().key())
        .authMethod(account.authMethod())
        .secretForm(account.secretForm())
        .state(account.state())
        .accountLabel(account.accountLabel())
        .released(account.released())
        .reconnectable(account.reconnectable())
        .notice(account.notice())
        .responsible(account.responsible())
        .connectedAt(account.connectedAt())
        .reconnectedAt(account.reconnectedAt())
        .expiresAt(account.expiresAt())
        .usedBy(
            account.usedBy().stream()
                .map(library -> new ConnectedAccountLibrary().id(library.id()).name(library.name()))
                .toList());
  }
}
