package io.opaa.permission;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The assets of one type a user may read, by the way of the rights formula that reaches them. */
public record ReadableAssets(
    Set<UUID> byDirectGrant, Set<UUID> byGroupGrant, Set<UUID> byAllAccountsGrant) {

  public ReadableAssets {
    byDirectGrant = Set.copyOf(byDirectGrant);
    byGroupGrant = Set.copyOf(byGroupGrant);
    byAllAccountsGrant = Set.copyOf(byAllAccountsGrant);
  }

  /** Every readable asset - a fresh, mutable set. */
  public Set<UUID> all() {
    Set<UUID> all = new HashSet<>(byDirectGrant);
    all.addAll(byGroupGrant);
    all.addAll(byAllAccountsGrant);
    return all;
  }
}
