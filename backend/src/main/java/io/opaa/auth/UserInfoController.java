package io.opaa.auth;

import io.opaa.api.dto.UserInfoResponse;
import io.opaa.auth.local.LocalAccountSelfDisclosure;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers from the {@link CurrentUser} snapshot {@link UserProvisioningFilter} took for this
 * request - the one caller load per request (#884). The only read of its own is the creation reason
 * of a local account (ADR-0033, Entscheidung 11), which lives in {@code local_credentials} and not
 * in the snapshot; it is part of the person's own self-disclosure and the user settings show it.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class UserInfoController {

  private final LocalAccountSelfDisclosure localAccounts;

  public UserInfoController(LocalAccountSelfDisclosure localAccounts) {
    this.localAccounts = localAccounts;
  }

  @GetMapping("/me")
  public UserInfoResponse me(@Caller CurrentUser caller) {
    return UserInfoResponseMapper.toResponse(
        caller, localAccounts.createdReasonOf(caller.id()).orElse(null));
  }
}
