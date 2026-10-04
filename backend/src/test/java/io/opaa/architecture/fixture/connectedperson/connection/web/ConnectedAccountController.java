package io.opaa.architecture.fixture.connectedperson.connection.web;

import io.opaa.architecture.fixture.connectedperson.connection.account.ConnectedAccount;

/** A person's own accounts may name them. */
public abstract class ConnectedAccountController {
  abstract ConnectedAccount own();
}
