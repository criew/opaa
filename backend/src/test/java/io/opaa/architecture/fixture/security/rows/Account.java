package io.opaa.architecture.fixture.security.rows;

/** Security state without a guard: a role next to a field a side path changes. */
public class Account {

  private String role;

  private String nickname;

  public String getRole() {
    return role;
  }

  public void rename(String nickname) {
    this.nickname = nickname;
  }

  /** Changes a field only through {@link #rename}. */
  public void renameTwice(String nickname) {
    rename(nickname);
    rename(nickname);
  }

  public static Account create() {
    return new Account();
  }
}
