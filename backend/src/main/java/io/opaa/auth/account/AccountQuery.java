package io.opaa.auth.account;

import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.ProviderType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.local.LocalUserQuery;
import io.opaa.common.ValidationException;
import java.util.UUID;

/**
 * Filter, sort and page of the account list of the administration (#1601). The bounds and the four
 * sort fields are those of {@link LocalUserQuery} - the activity class is deliberately not a sort
 * field (ADR-0033, Entscheidung 11). {@code status}, {@code withoutExpiry} and {@code inactive}
 * describe local accounts only and therefore narrow the list to them ({@link #localOnly()}).
 */
public record AccountQuery(
    String query,
    ProviderType providerType,
    UUID providerId,
    SystemRole role,
    LocalAccountState status,
    boolean withoutExpiry,
    boolean inactive,
    LocalUserQuery.Sort sort,
    boolean descending,
    int page,
    int size) {

  public static final int MAX_PAGE_SIZE = LocalUserQuery.MAX_PAGE_SIZE;
  public static final int DEFAULT_PAGE_SIZE = LocalUserQuery.DEFAULT_PAGE_SIZE;
  public static final int MAX_PAGE = LocalUserQuery.MAX_PAGE;
  public static final int MAX_QUERY_LENGTH = LocalUserQuery.MAX_QUERY_LENGTH;

  public AccountQuery {
    if (page < 0 || page > MAX_PAGE) {
      throw new ValidationException(
          "Die Seitennummer muss zwischen 0 und " + MAX_PAGE + " liegen.");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ValidationException(
          "Die Seitengröße muss zwischen 1 und " + MAX_PAGE_SIZE + " liegen.");
    }
    if (sort == null) {
      sort = LocalUserQuery.Sort.DISPLAY_NAME;
    }
    query = query == null || query.isBlank() ? null : query.trim();
    if (query != null && query.length() > MAX_QUERY_LENGTH) {
      throw new ValidationException(
          "Der Suchbegriff darf höchstens " + MAX_QUERY_LENGTH + " Zeichen lang sein.");
    }
  }

  /** Whether one of the filters that only a local account can satisfy is set. */
  public boolean localOnly() {
    return status != null || withoutExpiry || inactive;
  }
}
