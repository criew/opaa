package io.opaa.chat;

import java.util.List;

/**
 * One page of chat search matches, best first, with whether a further page exists - deliberately
 * without a total.
 */
public record ChatSearchPage(List<ChatSearchMatch> matches, boolean hasMore) {

  public ChatSearchPage {
    matches = List.copyOf(matches);
  }
}
