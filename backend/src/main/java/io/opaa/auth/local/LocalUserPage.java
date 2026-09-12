package io.opaa.auth.local;

import java.util.List;

/** One page of the local account list plus the total over all pages. */
public record LocalUserPage(List<LocalUserOverview> items, long total, int page, int size) {

  public LocalUserPage {
    items = List.copyOf(items);
  }
}
