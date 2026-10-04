package io.opaa.architecture.fixture.connectedperson.connection.web;

import io.opaa.architecture.fixture.connectedperson.connection.account.ConnectedAccount;
import io.opaa.architecture.fixture.connectedperson.connection.account.ConnectedAccountCounts;
import io.opaa.architecture.fixture.connectedperson.connection.account.PersonCount;

/** The administration: numbers pass, a connected account does not. */
public abstract class ConnectionProfileController {
  ConnectedAccountCounts counts;

  PersonCount count() {
    return counts.masked(3);
  }

  abstract ConnectedAccount account();
}
