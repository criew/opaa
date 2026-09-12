package io.opaa.auth.local;

import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.SystemRole;
import io.opaa.common.ValidationException;

/**
 * The filters, sort and page of the local account list (ADR-0033, Entscheidung 11). The sort fields
 * are an allow-list without the activity, and a page holds at most {@link #MAX_PAGE_SIZE} rows -
 * the list is a review instrument, not an export.
 *
 * @param query case-insensitive substring of address or display name, or {@code null}
 * @param withoutExpiry only accounts without an expiry date
 * @param inactive only accounts whose activity class is {@code NEVER} or {@code INACTIVE_90_DAYS}
 */
public record LocalUserQuery(
    String query,
    LocalAccountState status,
    SystemRole role,
    boolean withoutExpiry,
    boolean inactive,
    Sort sort,
    boolean descending,
    int page,
    int size) {

  public static final int MAX_PAGE_SIZE = 50;
  public static final int DEFAULT_PAGE_SIZE = 25;

  /**
   * Pages beyond this are refused; with the page size cap it keeps {@code page * size} in range.
   */
  public static final int MAX_PAGE = 100_000;

  public static final int MAX_QUERY_LENGTH = 320;

  public enum Sort {
    DISPLAY_NAME,
    EMAIL,
    EXPIRES_AT,
    CREATED_AT
  }

  public LocalUserQuery {
    if (page < 0 || page > MAX_PAGE) {
      throw new ValidationException(
          "Die Seitennummer muss zwischen 0 und " + MAX_PAGE + " liegen.");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ValidationException(
          "Die Seitengröße muss zwischen 1 und " + MAX_PAGE_SIZE + " liegen.");
    }
    if (sort == null) {
      sort = Sort.DISPLAY_NAME;
    }
    query = query == null || query.isBlank() ? null : query.trim();
    if (query != null && query.length() > MAX_QUERY_LENGTH) {
      throw new ValidationException(
          "Der Suchbegriff darf höchstens " + MAX_QUERY_LENGTH + " Zeichen lang sein.");
    }
  }
}
