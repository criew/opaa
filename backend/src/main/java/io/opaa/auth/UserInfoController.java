package io.opaa.auth;

import io.opaa.api.dto.UserInfoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers from the {@link CurrentUser} snapshot {@link UserProvisioningFilter} took for this
 * request - the one caller load per request (#884); the controller itself touches no repository.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class UserInfoController {

  @GetMapping("/me")
  public UserInfoResponse me(@Caller CurrentUser caller) {
    return new UserInfoResponse(
        caller.id(), caller.email(), caller.displayName(), caller.systemRole().name());
  }
}
