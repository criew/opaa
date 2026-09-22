package io.opaa.permission;

import java.util.Optional;
import java.util.UUID;

/**
 * The members of a group for the person who granted it a right on an object - "wer ein Recht gibt,
 * sieht, an wen" (#1880, ADR-0036 Entscheidung 9). Declared here because the paths that hold the
 * object-side right ask the question ({@code io.opaa.library}, {@code io.opaa.space}) and answered
 * by {@code io.opaa.group}, so the dependency direction stays {@code group} &rarr; {@code
 * permission} (Entscheidung 12).
 *
 * <p>Two of the ADR's four limits are decided here, because they are properties of the group:
 * released for use (b, with "not released" as the default, c) and the protection mark (d). The
 * other two belong to the caller: the object-side role and the right the group still holds there
 * (a) - neither is visible from this side.
 */
public interface GroupMemberDisclosureDirectory {

  /** The largest page this read hands out, whatever a caller asks for. */
  int MAX_PAGE_SIZE = 200;

  /**
   * The group's active members, or empty where the group must not be disclosed at all: no group of
   * that id, a group of another organization, or an internal group its stewards have not released
   * for use. Every caller turns the empty answer into the answer an unknown group gets, {@code
   * 404}.
   *
   * <p>A protected group is disclosed <b>without</b> name, count and members - only with the people
   * to ask instead ({@link GroupMemberDisclosure#responsible()}).
   *
   * <p>{@code limit} is clamped to {@link #MAX_PAGE_SIZE} and becomes the {@code LIMIT} of the
   * query, so a group of five thousand never becomes five thousand rows in memory.
   */
  Optional<GroupMemberDisclosure> disclose(
      UUID groupId, UUID organizationId, int offset, int limit);
}
