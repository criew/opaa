package io.opaa.permission;

import java.util.Set;

/**
 * The scopes of one scoped capability a caller holds. {@code all} stands for the system
 * administration, which holds every scope implicitly - including one created after this answer.
 */
public record HeldScopes(boolean all, Set<String> scopes) {

  static final HeldScopes ALL = new HeldScopes(true, Set.of());

  public HeldScopes {
    scopes = Set.copyOf(scopes);
  }

  /** Whether {@code scope} is held. */
  public boolean covers(String scope) {
    return all || scopes.contains(scope);
  }

  /** Whether at least one scope is held. */
  public boolean any() {
    return all || !scopes.isEmpty();
  }
}
