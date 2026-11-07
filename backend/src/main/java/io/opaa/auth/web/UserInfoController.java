package io.opaa.auth.web;

import io.opaa.account.LocalAccountSelfDisclosure;
import io.opaa.api.dto.UserInfoResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.QuickTourHint;
import io.opaa.auth.UserProvisioningFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers from the {@link CurrentUser} snapshot {@link UserProvisioningFilter} took for this
 * request - the one caller load per request. The only read of its own is the creation reason of a
 * local account (ADR-0033, Entscheidung 11), which lives in {@code local_credentials} and not in
 * the snapshot; it is part of the person's own self-disclosure and the user settings show it. The
 * marker of the quick-tour hint is read here as well, whether or not the quick tour is switched on.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class UserInfoController {

  private final LocalAccountSelfDisclosure localAccounts;
  private final QuickTourHint quickTourHint;

  public UserInfoController(LocalAccountSelfDisclosure localAccounts, QuickTourHint quickTourHint) {
    this.localAccounts = localAccounts;
    this.quickTourHint = quickTourHint;
  }

  @GetMapping("/me")
  public UserInfoResponse me(@Caller CurrentUser caller) {
    return UserInfoResponseMapper.toResponse(
        caller,
        localAccounts.createdReasonOf(caller.id()).orElse(null),
        quickTourHint.isDone(caller.id()));
  }
}
