package io.opaa.api;

import io.opaa.auth.local.LocalUserQuery;
import io.opaa.common.ValidationException;
import java.util.Locale;

/**
 * The sort parameters the two account lists of the administration share ({@code /admin/local-users}
 * and {@code /admin/accounts}): an allow-list of four fields - the activity class is deliberately
 * not one of them (ADR-0033, Entscheidung 11) - and the two directions. Anything else is a 400.
 */
final class AdminListSortParams {

  private AdminListSortParams() {}

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
