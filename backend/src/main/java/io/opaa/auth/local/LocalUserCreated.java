package io.opaa.auth.local;

/**
 * The outcome of creating a local account: the account, and exactly one of the two secrets that are
 * handed out once - the invitation's {@link LinkDelivery} or the generated initial password.
 */
public record LocalUserCreated(
    LocalUserOverview account, LinkDelivery delivery, String initialPassword) {

  /** Never the password. */
  @Override
  public String toString() {
    return "LocalUserCreated[account="
        + account.user().getId()
        + ", delivery="
        + delivery
        + ", initialPassword="
        + (initialPassword == null ? "none" : "***")
        + "]";
  }
}
