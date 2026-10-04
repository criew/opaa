package io.opaa.architecture.fixture.connectedperson.connection.oauth;

import io.opaa.architecture.fixture.connectedperson.connection.account.ConnectedAccountService;

/** The OAuth flow completes a consent through the same step. */
public class Consent {
  void complete(ConnectedAccountService accounts) {
    accounts.established("p");
  }
}
