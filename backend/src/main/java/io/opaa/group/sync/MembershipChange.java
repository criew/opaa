package io.opaa.group.sync;

import java.util.List;

/**
 * Which users would gain or lose membership of one group - the level of detail an admin needs to
 * decide before a run that would revoke access is applied (see #237's acceptance criteria). Domain
 * counterpart of the generated {@code DirectorySyncMembershipChange}, mapped by {@code
 * io.opaa.api.DirectorySyncResponseMapper}.
 */
public record MembershipChange(
    String externalId, String name, List<UserRef> added, List<UserRef> removed) {}
