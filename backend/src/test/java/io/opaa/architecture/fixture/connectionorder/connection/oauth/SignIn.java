package io.opaa.architecture.fixture.connectionorder.connection.oauth;

/** Points downward to the accounts, the profiles and the token store, as it may. */
public class SignIn {
  io.opaa.architecture.fixture.connectionorder.connection.account.Account account;
  io.opaa.architecture.fixture.connectionorder.connection.profile.Profile profile;
  io.opaa.architecture.fixture.connectionorder.connection.token.Store store;
}
