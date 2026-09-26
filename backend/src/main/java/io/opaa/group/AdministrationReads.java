package io.opaa.group;

import io.opaa.auth.CurrentUser;
import java.util.UUID;

/**
 * The one rule deciding whether a caller reads a group as the administration rather than as one of
 * its stewards (ADR-0036, Entscheidung 9). In that case a member list is an audit event, and only
 * the recorded retrievals hand it out - the group administration's {@code listMembers} as well as
 * the disclosure through an object.
 */
final class AdministrationReads {

  private AdministrationReads() {}

  static boolean readsAsAdministration(
      GroupStewardRepository stewardRepository, UUID groupId, CurrentUser caller) {
    return caller.isSystemAdmin()
        && !stewardRepository.existsByGroupIdAndUserId(groupId, caller.id());
  }
}
