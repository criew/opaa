package io.opaa.indexing.metadata;

import io.opaa.llm.ActiveChatModelDescription;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
   * Cached per base address: the verdict cannot change without the address changing, and the
   * settings screen must not wait on a name resolution on every request. One entry per configured
   * chat role, so the map stays at a handful of entries.
   */
  private static final Map<String, Boolean> LOCAL_BY_BASE_URL = new ConcurrentHashMap<>();

  /**
   * Loopback, link-local and site-local addresses count as local. A host name that does not resolve
   * counts as remote: the warning must never be softened by a lookup that merely failed.
   */
  static boolean isLocal(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    return LOCAL_BY_BASE_URL.computeIfAbsent(baseUrl, ChatRoleSummary::resolveLocal);
  }

  private static boolean resolveLocal(String baseUrl) {
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
