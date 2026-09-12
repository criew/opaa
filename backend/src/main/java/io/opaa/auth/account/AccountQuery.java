package io.opaa.auth.account;

import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.ProviderType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.local.LocalUserQuery;
import io.opaa.common.ValidationException;
import java.util.UUID;

/**
 * Filter, sort and page of the account list of the administration (#1601). The bounds are those of
 * {@link LocalUserQuery}. {@code status}, {@code withoutExpiry} and {@code inactive} describe local
 * accounts only and therefore narrow the list to them ({@link #localOnly()}).
 */
public record AccountQuery(
    String query,
    ProviderType providerType,
    UUID providerId,
    SystemRole role,
    LocalAccountState status,
    boolean withoutExpiry,
    boolean inactive,
    Sort sort,
    boolean descending,
    int page,
    int size) {

  public static final int MAX_PAGE_SIZE = LocalUserQuery.MAX_PAGE_SIZE;
  public static final int DEFAULT_PAGE_SIZE = LocalUserQuery.DEFAULT_PAGE_SIZE;
  public static final int MAX_PAGE = LocalUserQuery.MAX_PAGE;
  public static final int MAX_QUERY_LENGTH = LocalUserQuery.MAX_QUERY_LENGTH;

  /**
   * The sort fields of the account list. The four of {@link LocalUserQuery.Sort} plus the three
   * columns the list gained with the provider accounts (#1601). The activity class is deliberately
   * not among them and never will be (ADR-0033, Entscheidung 11): a list sortable by "last used" is
   * the evaluation path that decision rules out. The three added ones carry no such risk - origin,
   * role and state say where an account comes from and whether it can sign in, not when someone
   * worked.
   */
  public enum Sort {
    DISPLAY_NAME,
    EMAIL,
    EXPIRES_AT,
    CREATED_AT,
    /** Local accounts first, then the providers by name; an unknown issuer last. */
    ORIGIN,
    /** By privilege: Nutzer, Revision, Systemverwaltung - the order the labels read in. */
    ROLE,
    /** By what needs attention: gesperrt, abgelaufen, eingeladen, aktiv; provider accounts last. */
    STATUS
  }

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
      sort = Sort.DISPLAY_NAME;
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
