package io.opaa.api;

import io.opaa.auth.account.AccountQuery;
import io.opaa.auth.local.LocalUserQuery;
import io.opaa.common.ValidationException;
import java.util.Locale;

/**
 * The sort parameters of the two account lists of the administration ({@code /admin/local-users}
 * and {@code /admin/accounts}): an allow-list per list - the activity class is in neither of them
 * (ADR-0033, Entscheidung 11) - and the one direction parser both use. Anything else is a 400.
 */
final class AdminListSortParams {

  private AdminListSortParams() {}

  /**
   * The account list of #1601 sorts by three more fields than the local one: the columns it gained
   * with the provider accounts. The activity is not among them here either.
   */
  static AccountQuery.Sort accountSortOf(String sort) {
    return switch (sort == null ? "" : sort.trim()) {
      case "displayName", "" -> AccountQuery.Sort.DISPLAY_NAME;
      case "email" -> AccountQuery.Sort.EMAIL;
      case "expiresAt" -> AccountQuery.Sort.EXPIRES_AT;
      case "createdAt" -> AccountQuery.Sort.CREATED_AT;
      case "origin" -> AccountQuery.Sort.ORIGIN;
      case "role" -> AccountQuery.Sort.ROLE;
      case "status" -> AccountQuery.Sort.STATUS;
      default ->
          throw new ValidationException(
              "Sortierung nur nach displayName, email, origin, role, status, expiresAt oder"
                  + " createdAt.");
    };
  }

  static LocalUserQuery.Sort sortOf(String sort) {
    return switch (sort == null ? "" : sort.trim()) {
      case "displayName", "" -> LocalUserQuery.Sort.DISPLAY_NAME;
      case "email" -> LocalUserQuery.Sort.EMAIL;
      case "expiresAt" -> LocalUserQuery.Sort.EXPIRES_AT;
      case "createdAt" -> LocalUserQuery.Sort.CREATED_AT;
      default ->
          throw new ValidationException(
              "Sortierung nur nach displayName, email, expiresAt oder createdAt.");
    };
  }

  static boolean descending(String direction) {
    return switch (direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT)) {
      case "asc", "" -> false;
      case "desc" -> true;
      default -> throw new ValidationException("Sortierrichtung nur asc oder desc.");
    };
  }
}
