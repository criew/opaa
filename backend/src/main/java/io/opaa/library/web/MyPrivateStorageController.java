package io.opaa.library.web;

import io.opaa.api.dto.PrivateStorageUsageResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.knowledge.PersonalStorageQuota;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's own use across her private libraries - the one place outside the enforcement that
 * reads a person's use, and only the caller's ({@code
 * ModularArchitecture#personalUsageIsReadOnlyByItsOwner}).
 */
@RestController
public class MyPrivateStorageController {

  private final PersonalStorageQuota quota;

  public MyPrivateStorageController(PersonalStorageQuota quota) {
    this.quota = quota;
  }

  @GetMapping("/api/v1/me/private-storage")
  public PrivateStorageUsageResponse getMyPrivateStorage(@Caller CurrentUser caller) {
    return new PrivateStorageUsageResponse(quota.usageOf(caller.id()), quota.quotaBytes());
  }
}
