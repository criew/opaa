package io.opaa.auth.account;

import java.util.List;

/** One page of the account list; {@code total} counts the matches over all pages. */
public record AccountPage(List<AccountOverview> items, long total, int page, int size) {
  public AccountPage {
    items = List.copyOf(items);
  }
}
