package io.opaa.group.web;

import io.opaa.common.ValidationException;
import io.opaa.group.GroupListQuery;

/** The sort fields of the group list of the administration; anything else is a 400. */
final class GroupListSortParams {

  private GroupListSortParams() {}

  static GroupListQuery.Sort groupSortOf(String sort) {
    return switch (sort == null ? "" : sort.trim()) {
      case "name", "" -> GroupListQuery.Sort.NAME;
      case "kind" -> GroupListQuery.Sort.KIND;
      case "origin" -> GroupListQuery.Sort.ORIGIN;
      case "memberCount" -> GroupListQuery.Sort.MEMBER_COUNT;
      case "state" -> GroupListQuery.Sort.STATE;
      case "createdAt" -> GroupListQuery.Sort.CREATED_AT;
      default ->
          throw new ValidationException(
              "Sortierung nur nach name, kind, origin, memberCount, state oder createdAt.");
    };
  }
}
