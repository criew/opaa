package io.opaa.auth;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The list a token carries under a claim path, classified: either the values it named - an empty
 * list included, which is the provider saying "none" - or why it named nothing usable. {@link
 * TokenGroups} (#1807) and {@link TokenRoles} (#1830) read through this one classification, so "the
 * claim is empty" and "there is no usable claim" cannot drift apart between them; each wraps the
 * outcome in its own type, because only the caller knows what an empty claim means for it.
 */
sealed interface ClaimList {

  /** OpenID Connect 5.6.2: the names of the claims delivered as aggregated or distributed. */
  String OVERAGE_CLAIM = "_claim_names";

  /** The claim's values, blank and non-string entries dropped; empty means it named none. */
  record Values(List<String> values) implements ClaimList {
    public Values {
      values = List.copyOf(values);
    }
  }

  /** The claim said nothing usable, for {@link Cause}. */
  record Unusable(Cause cause) implements ClaimList {}

  /** Why a token carries no usable value under a claim path. */
  enum Cause {
    /** No value under the configured path - the provider's mapper is gone, renamed or never set. */
    MISSING,
    /** A value of another shape, or one that names nothing usable. */
    MALFORMED,
    /**
     * The provider replaced the claim by a reference because it grew too large (OpenID Connect
     * 5.6.2 distributed claims; Entra ID does this above 200 groups).
     */
    OVERAGE
  }

  /**
   * The value under {@code path}, classified. A {@code null} or blank path - a provider that names
   * no such claim at all - reads as {@link Cause#MISSING}: nothing is known, so nothing may be
   * derived.
   */
  static ClaimList read(Map<String, Object> claims, String path) {
    if (signalsOverage(claims, path)) {
      return new Unusable(Cause.OVERAGE);
    }
    Object value = ClaimPaths.valueAt(claims, path);
    if (value == null) {
      return new Unusable(Cause.MISSING);
    }
    if (value instanceof Collection<?> raw) {
      // an empty list is the provider saying "none"; a non-empty one that yields no value is a
      // claim this reader cannot make sense of, and never a revocation
      List<String> values = usableValues(raw);
      return raw.isEmpty() || !values.isEmpty()
          ? new Values(values)
          : new Unusable(Cause.MALFORMED);
    }
    if (value instanceof String text && !text.isBlank()) {
      return new Values(List.of(text));
    }
    return new Unusable(Cause.MALFORMED);
  }

  private static List<String> usableValues(Collection<?> values) {
    return values.stream()
        .filter(element -> element instanceof String text && !text.isBlank())
        .map(String.class::cast)
        .toList();
  }

  /**
   * Whether the token replaced the claim by a distributed-claim reference: {@code _claim_names}
   * maps the top-level claim name - the first segment of {@code path} - to a source entry. The
   * split keeps its limit: without it a path of nothing but dots yields an empty array and reading
   * its first segment would fail the request, which no claim layout may do.
   */
  private static boolean signalsOverage(Map<String, Object> claims, String path) {
    if (claims == null || path == null || path.isBlank()) {
      return false;
    }
    return claims.get(OVERAGE_CLAIM) instanceof Map<?, ?> names
        && names.containsKey(path.split("\\.", 2)[0]);
  }
}
