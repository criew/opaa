package io.opaa.permission;

import io.opaa.auth.CurrentUser;
import java.util.Optional;
import java.util.UUID;

/**
 * The members of a group for the person who granted it a right on an object - "wer ein Recht gibt,
 * sieht, an wen" (#1880, ADR-0036 Entscheidung 9). Declared here because the paths holding the
 * object-side right ask ({@code io.opaa.library}, {@code io.opaa.space}) and answered by {@code
 * io.opaa.group}, so the dependency direction stays {@code group} &rarr; {@code permission}
 * (Entscheidung 12).
 *
 * <p><b>This interface is the one place the disclosure rule is written down.</b> Four of the five
 * limits are properties of the group and decided by the implementation; the fifth belongs to the
 * caller and is enforced by each caller before it asks:
 *
 * <ul>
 *   <li><b>(a) the right held at the object</b> - <em>the caller's</em>: only while the group still
 *       holds a grant there or is still a member of the space.
 *   <li><b>(b) released for use</b>, with <b>(c) "not released" as the delivered default</b>. A
 *       provider group needs no release - its existence is not a decision of this house.
 *   <li><b>(d) never for a protected group</b>: no name, no size, no members - only the people to
 *       ask instead ({@link GroupMemberDisclosure#responsible()}).
 *   <li><b>(e) never below the Mindestgruppengröße</b> ({@link GroupSizeProperties}): no members
 *       and no figure, only {@link GroupMemberDisclosure#smallGroup()}. The same suppression the
 *       growth signal of the very same row carries (Auflage A2) - without it, one row would say
 *       "kleine Gruppe" on the left and name four people on the right. Provisional decision of the
 *       coordinator (22.09.2026, #1882), taken in favour of data thrift while the maintainer was
 *       unavailable.
 * </ul>
 *
 * <p>Every one of them answers {@link Optional#empty()} or a withheld field rather than an
 * explanation, and every caller turns the empty answer into the answer an unknown group gets,
 * {@code 404}.
 *
 * <p><b>A retrieval carried by the system role is an audit event</b> ({@code GROUP_MEMBERS_READ},
 * ADR-0036 Entscheidung 9 and Auflage A6), written by the implementation so both objects are
 * covered by one rule: a system administrator passes the threshold of every library and every
 * space, so the event hangs on the caller, not on the endpoint. A steward reading their own group
 * writes nothing, and so does a grant giver without the system role - they read whom they brought
 * to their own object, and the grant names them with a timestamp.
 */
public interface GroupMemberDisclosureDirectory {

  /** The largest page this read hands out, whatever a caller asks for. */
  int MAX_PAGE_SIZE = 200;

  /**
   * The group's members under the rule above, or empty where it must not be disclosed at all: no
   * group of that id, a group of another organization, or an unreleased internal group.
   *
   * <p>{@code limit} is clamped to {@link #MAX_PAGE_SIZE} and becomes the {@code LIMIT} of the
   * query, so a group of five thousand never becomes five thousand rows in memory.
   */
  Optional<GroupMemberDisclosure> disclose(
      UUID groupId, UUID organizationId, CurrentUser caller, int offset, int limit);
}
