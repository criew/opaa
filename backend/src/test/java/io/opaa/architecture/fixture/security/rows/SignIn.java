package io.opaa.architecture.fixture.security.rows;

/** The entry of the fixture sign-in path. */
public class SignIn {

  private final TokenStep step;

  public SignIn(TokenStep step) {
    this.step = step;
  }

  public void signIn(VersionedToken token) {
    step.apply(token);
  }
}
