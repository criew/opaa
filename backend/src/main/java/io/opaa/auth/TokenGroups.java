package io.opaa.auth;

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

    /**
     * The shared reader classifies, this names the cause in the wording of a groups claim; the
     * switch is exhaustive, so a further cause has to be worded here as well.
     */
    static Reason of(ClaimList.Cause cause) {
      return switch (cause) {
        case MISSING -> CLAIM_MISSING;
        case MALFORMED -> CLAIM_MALFORMED;
        case OVERAGE -> CLAIM_OVERAGE;
      };
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
   * The value under {@code path}, classified by {@link ClaimList}. A {@code null} or blank path - a
   * provider that names no groups claim at all - reads as {@link Reason#CLAIM_MISSING}: nothing is
   * known about this account's groups, so nothing about them may change.
   */
  static TokenGroups read(Map<String, Object> claims, String path) {
    return switch (ClaimList.read(claims, path)) {
      case ClaimList.Values values -> named(values.values());
      case ClaimList.Unusable unusable -> unavailable(Reason.of(unusable.cause()));
    };
  }
}
