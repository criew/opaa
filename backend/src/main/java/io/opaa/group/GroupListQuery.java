package io.opaa.group;

import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupOrigin;
import io.opaa.api.types.GroupState;
import io.opaa.common.ValidationException;
import java.util.Locale;
import java.util.UUID;

/**
 * Search, filter, sort and page of the administration's group list (#1978), bounded like the
 * account list. {@code providerId} implies {@link GroupOrigin#PROVIDER}; a contradicting {@code
 * origin} is refused rather than silently answered with an empty page.
 */
public record GroupListQuery(
    String query,
    GroupOrigin origin,
    UUID providerId,
    GroupKind kind,
    GroupState state,
    Sort sort,
    boolean descending,
    int page,
    int size) {

  public static final int MAX_PAGE_SIZE = 50;
  public static final int DEFAULT_PAGE_SIZE = 25;
  public static final int MAX_PAGE = 100_000;
  public static final int MAX_QUERY_LENGTH = 200;

  public enum Sort {
    NAME,
    /** By the label read in the list: Ad-hoc-Gruppe, Gruppe aus dem Identitätsanbieter, OE. */
    KIND,
    /** Internal groups first, then the providers by name. */
    ORIGIN,
    MEMBER_COUNT,
    /** What needs a decision first: dissolved, provider disabled, unmaintained, not released. */
    STATE,
    CREATED_AT
  }

  public GroupListQuery {
    if (page < 0 || page > MAX_PAGE) {
      throw new ValidationException(
          "Die Seitennummer muss zwischen 0 und " + MAX_PAGE + " liegen.");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ValidationException(
          "Die Seitengröße muss zwischen 1 und " + MAX_PAGE_SIZE + " liegen.");
    }
    if (providerId != null && origin == GroupOrigin.INTERNAL) {
      throw new ValidationException(
          "Ein Anbieter lässt sich nicht mit der Herkunft „intern“ kombinieren.");
    }
    if (sort == null) {
      sort = Sort.NAME;
    }
    query = query == null || query.isBlank() ? null : query.trim();
    if (query != null && query.length() > MAX_QUERY_LENGTH) {
      throw new ValidationException(
          "Der Suchbegriff darf höchstens " + MAX_QUERY_LENGTH + " Zeichen lang sein.");
    }
  }

  /** Whether a group passes every filter; {@code provider} is null for an internal group. */
  boolean matches(Group group, GroupProviderView provider, GroupState groupState) {
    if (origin == GroupOrigin.INTERNAL && provider != null) return false;
    if (origin == GroupOrigin.PROVIDER && provider == null) return false;
    if (providerId != null && !providerId.equals(group.getProviderId())) return false;
    if (kind != null && group.getKind() != kind) return false;
    if (state != null && groupState != state) return false;
    return query == null || containsQuery(group);
  }

  private boolean containsQuery(Group group) {
    String needle = query.toLowerCase(Locale.ROOT);
    return contains(group.getName(), needle)
        || contains(group.getDescription(), needle)
        || contains(group.getSourcePath(), needle);
  }

  private static boolean contains(String text, String needle) {
    return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
  }
}
