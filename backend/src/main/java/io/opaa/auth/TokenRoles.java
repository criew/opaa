package io.opaa.auth;

import java.util.List;
import java.util.Map;

/**
 * What a token says about its bearer's system role - and whether it says anything at all (#1830). A
 * provider's roles claim is authoritative: the values it carries decide {@code SYSTEM_ADMIN},
 * {@code AUDITOR} or {@code USER}, and a claim that names none of the configured role values is
 * therefore a withdrawal. A token that carries no usable claim says nothing, and no role may be
 * derived from it. The two are different values here so that no caller can fold them back into one
 * empty list - an empty list would read as {@code USER} and silently demote every administrator and
 * auditor of a provider whose role mapper is gone.
 */
public sealed interface TokenRoles {

  /** The claim's role values; empty means the provider grants none of the elevated roles. */
  record Named(List<String> values) implements TokenRoles {
    public Named {
      values = List.copyOf(values);
    }
  }

  /** The claim carried nothing usable: the stored system role stays untouched. */
  record Unavailable(Reason reason) implements TokenRoles {}

  /** Why a token carries no usable roles claim; the text is the cause phrase of the log line. */
  enum Reason {
    /** No value under the configured path - the provider's mapper is gone, renamed or never set. */
    CLAIM_MISSING("carries no roles claim"),
    /** A value of another shape, or one that names nothing usable. */
    CLAIM_MALFORMED("carries a roles claim of an unexpected shape"),
    /**
     * The provider replaced the claim by a reference because it grew too large (OpenID Connect
     * 5.6.2 distributed claims; Entra ID does this above 200 entries).
     */
    CLAIM_OVERAGE("replaces the roles claim by a '_claim_names' reference (claim overage)");

    private final String description;

    Reason(String description) {
      this.description = description;
    }

    /** How this cause reads in a log line about the provider. */
    public String description() {
      return description;
    }

    /**
     * The shared reader classifies, this names the cause in the wording of a roles claim; the
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
  static TokenRoles unavailable(Reason reason) {
    return new Unavailable(reason);
  }

  /** The role values a token named, for a caller that does not read claims itself. */
  static TokenRoles named(List<String> values) {
    return new Named(values);
  }

  /**
   * The value under {@code path}, classified by {@link ClaimList}. A {@code null} or blank path - a
   * provider that names no roles claim at all - reads as {@link Reason#CLAIM_MISSING}: nothing is
   * known about this account's roles, so nothing about them may change.
   */
  static TokenRoles read(Map<String, Object> claims, String path) {
    return switch (ClaimList.read(claims, path)) {
      case ClaimList.Values values -> named(values.values());
      case ClaimList.Unusable unusable -> unavailable(Reason.of(unusable.cause()));
    };
  }
}
