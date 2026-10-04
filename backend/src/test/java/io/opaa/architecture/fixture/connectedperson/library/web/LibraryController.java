package io.opaa.architecture.fixture.connectedperson.library.web;

import io.opaa.architecture.fixture.connectedperson.connection.account.ConnectedAccount;

/** Another module's web layer reaching a connected account. */
public abstract class LibraryController {
  abstract ConnectedAccount ownerAccount();
}
