package io.opaa.architecture.fixture.connectedperson.library;

import io.opaa.architecture.fixture.connectedperson.connection.account.ConnectedAccountService;

/** Stores a person's secret past the sign-in. */
public class AccountShortcut {
  void store(ConnectedAccountService accounts) {
    accounts.established("p");
  }
}
