package io.opaa.indexing.metadata;

import io.opaa.llm.ActiveChatModelDescription;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * The two facts about the active chat role the Datenschutzhinweis at the extraction switch needs -
 * Basis-Adresse and Modell-Kennung, never an access key - plus whether that address is local. A
 * locally operated model means the extraction runs without an outgoing connection, which is what
 * decides between the two very different warnings a client shows.
 */
public record ChatRoleSummary(String baseUrl, String modelIdentifier, boolean local) {

  public static ChatRoleSummary of(ActiveChatModelDescription description) {
    return new ChatRoleSummary(
        description.baseUrl(), description.modelIdentifier(), isLocal(description.baseUrl()));
  }

  /**
   * Loopback, link-local and site-local addresses count as local. A host name that does not resolve
   * counts as remote: the warning must never be softened by a lookup that merely failed.
   */
  static boolean isLocal(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    try {
      String host = URI.create(baseUrl.strip()).getHost();
      if (host == null) {
        return false;
      }
      if ("localhost".equalsIgnoreCase(host)) {
        return true;
      }
      InetAddress address = InetAddress.getByName(host);
      return address.isLoopbackAddress()
          || address.isLinkLocalAddress()
          || address.isSiteLocalAddress();
    } catch (IllegalArgumentException | UnknownHostException | SecurityException e) {
      return false;
    }
  }
}
