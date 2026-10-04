package io.opaa.architecture.fixture.connectedperson.connection.web;

import io.opaa.architecture.fixture.connectedperson.connection.account.ConnectedAccount;
import io.opaa.architecture.fixture.connectedperson.connection.profile.PersonNumbers;

/** The administration: masked numbers pass, a connected account does not. */
public abstract class ConnectionProfileController {
  PersonNumbers numbers;

  String count() {
    return numbers.totalOf("p");
  }

  abstract ConnectedAccount account();
}
