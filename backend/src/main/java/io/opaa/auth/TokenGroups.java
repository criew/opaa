package io.opaa.auth;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * What a token says about its bearer's groups - and whether it says anything at all (#1807). A
 * provider's groups claim is authoritative: the names it carries are exactly the memberships in
 * that provider's namespace, an empty claim therefore ends all of them. A token that carries no
 * usable claim says nothing, and nothing may be derived from it. The two are different values here
 * so that no caller can fold them back into one empty list.
 */
public sealed interface TokenGroups {

  /** OpenID Connect 5.6.2: the names of the claims delivered as aggregated or distributed. */
  String OVERAGE_CLAIM = "_claim_names";

  /**
   * The claim's group names; empty means the provider revokes every membership of its namespace.
   */
  record Named(List<String> names) implements TokenGroups {
    public Named {
      names = List.copyOf(names);
    }
  }

  /** The claim carried nothing usable: the last known memberships stay untouched. */
  record Unavailable(Reason reason) implements TokenGroups {}

  /** Why a token carries no usable groups claim; the text is the cause phrase of the log line. */
  enum Reason {
    /** No value under the configured path - the provider's mapper is gone, renamed or never set. */
    CLAIM_MISSING("carries no groups claim"),
    /** A value of another shape, or one that names nothing usable. */
    CLAIM_MALFORMED("carries a groups claim of an unexpected shape"),
    /**
     * The provider replaced the claim by a reference because it grew too large (OpenID Connect
     * 5.6.2 distributed claims; Entra ID does this above 200 groups).
     */
    CLAIM_OVERAGE("replaces the groups claim by a '_claim_names' reference (group overage)");

    private final String description;

    Reason(String description) {
      this.description = description;
    }

    /** How this cause reads in a log line about the provider. */
    public String description() {
      return description;
    }
  }

  /** The claim named nothing usable, for the given reason. */
  static TokenGroups unavailable(Reason reason) {
    return new Unavailable(reason);
  }

  /** The group names a token named, for a caller that does not read claims itself. */
  static TokenGroups named(List<String> names) {
    return new Named(names);
  }

  /**
   * The value under {@code path}, classified. A {@code null} or blank path - a provider that names
   * no groups claim at all - reads as {@link Reason#CLAIM_MISSING}: nothing is known about this
   * account's groups, so nothing about them may change.
   */
  static TokenGroups read(Map<String, Object> claims, String path) {
    if (signalsOverage(claims, path)) {
      return unavailable(Reason.CLAIM_OVERAGE);
    }
    Object value = ClaimPaths.valueAt(claims, path);
    if (value == null) {
      return unavailable(Reason.CLAIM_MISSING);
    }
    if (value instanceof Collection<?> values) {
      // an empty list is the provider saying "no groups"; a non-empty one that yields no name is
      // a claim this reader cannot make sense of, and never a revocation
      List<String> names = usableNames(values);
      return values.isEmpty() || !names.isEmpty()
          ? named(names)
          : unavailable(Reason.CLAIM_MALFORMED);
    }
    if (value instanceof String text && !text.isBlank()) {
      return named(List.of(text));
    }
    return unavailable(Reason.CLAIM_MALFORMED);
  }

  private static List<String> usableNames(Collection<?> values) {
    return values.stream()
        .filter(element -> element instanceof String text && !text.isBlank())
        .map(String.class::cast)
        .toList();
  }

  /**
   * Whether the token replaced the claim by a distributed-claim reference: {@code _claim_names}
   * maps the top-level claim name - the first segment of {@code path} - to a source entry.
   */
  private static boolean signalsOverage(Map<String, Object> claims, String path) {
    if (claims == null || path == null || path.isBlank()) {
      return false;
    }
    return claims.get(OVERAGE_CLAIM) instanceof Map<?, ?> names
        && names.containsKey(path.split("\\.")[0]);
  }
}
