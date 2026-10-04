package io.opaa.connection.web;

import io.opaa.api.dto.ConnectedAccount;
import io.opaa.api.dto.ConnectedAccountConnectRequest;
import io.opaa.api.dto.ConnectedAccountsOverview;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.account.ConnectedAccountService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's own connected accounts ("Verbundene Konten"): every signed-in person, only their own.
 * No answer carries a secret.
 */
@RestController
public class ConnectedAccountController {

  private static final String PATH = "/api/v1/me/connected-accounts";

  private final ConnectedAccountService accounts;

  public ConnectedAccountController(ConnectedAccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping(PATH)
  public ConnectedAccountsOverview listMyConnectedAccounts(@Caller CurrentUser caller) {
    return ConnectedAccountResponseMapper.toResponse(accounts.overview(caller));
  }

  @PutMapping(PATH + "/{profileId}")
  public ConnectedAccount connectMyAccount(
      @PathVariable UUID profileId,
      @Valid @RequestBody ConnectedAccountConnectRequest request,
      @Caller CurrentUser caller) {
    return ConnectedAccountResponseMapper.toResponse(
        accounts.connect(caller, profileId, request.getUsername(), request.getSecret()));
  }

  @DeleteMapping(PATH + "/{profileId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void disconnectMyAccount(@PathVariable UUID profileId, @Caller CurrentUser caller) {
    accounts.disconnect(caller, profileId);
  }
}
