package io.opaa.architecture.fixture.security.rows;

import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

/** Every way to write a row of the fixture, guarded and unguarded. */
public class AccountWriter {

  private final AccountRepository accounts;
  private final VersionedTokenRepository tokens;
  private final DynamicGroupRepository groups;
  private final LedgerRepository ledgers;

  public AccountWriter(
      AccountRepository accounts,
      VersionedTokenRepository tokens,
      DynamicGroupRepository groups,
      LedgerRepository ledgers) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.groups = groups;
    this.ledgers = ledgers;
  }

  /** Changes a loaded instance without a save: flushed as a whole row. */
  public void rename(Account account) {
    account.renameTwice("neu");
  }

  public Account insert() {
    return accounts.save(Account.create());
  }

  public UnaryOperator<Account> saver() {
    return accounts::save;
  }

  public void revoke(VersionedToken token) {
    token.revoke(Instant.now());
    tokens.save(token);
  }

  public void deactivate(DynamicGroup group) {
    group.deactivate();
  }

  public void deactivateAll(List<DynamicGroup> all) {
    all.forEach(DynamicGroup::deactivate);
    groups.saveAll(all);
  }

  public void annotate(Ledger ledger) {
    ledger.annotate("x");
    ledgers.save(ledger);
  }

  public String readRole(Account account) {
    return account.getRole();
  }
}
