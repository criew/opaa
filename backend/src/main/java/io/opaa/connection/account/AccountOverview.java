package io.opaa.connection.account;

import io.opaa.api.types.ConnectedAccountState;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.PersonalSecretForm;
import io.opaa.knowledge.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one person sees of their own connected accounts: every connection, the profiles they may
 * connect now, and who to ask for a missing one. For that person only; never for the
 * administration.
 */
public record AccountOverview(
    List<Account> accounts, List<Connectable> connectable, MissingAccess missingAccess) {

  /**
   * One connection of the person.
   *
   * @param accountLabel the person's own account name at the provider, {@code null} for none
   * @param notice German note on state or release, {@code null} when there is nothing to say
   * @param responsible who can change what the notice says, {@code null} with it
   * @param expiresAt when the provider ends the OAuth consent, {@code null} where it names no end
   */
  public record Account(
      UUID profileId,
      String profileName,
      SourceType sourceType,
      ConnectionAuthMethod authMethod,
      PersonalSecretForm secretForm,
      ConnectedAccountState state,
      String accountLabel,
      boolean released,
      boolean reconnectable,
      String notice,
      String responsible,
      Instant connectedAt,
      Instant reconnectedAt,
      Instant expiresAt,
      List<Library> usedBy) {

    @Override
    public String toString() {
      return "Account[profileId=" + profileId + ", state=" + state + "]";
    }
  }

  /** A private library of the person on the connection. */
  public record Library(UUID id, String name) {}

  /** A profile the person may connect an account on now; {@code secretForm} null for OAuth. */
  public record Connectable(
      UUID profileId,
      String name,
      SourceType sourceType,
      ConnectionAuthMethod authMethod,
      PersonalSecretForm secretForm) {}

  /** The answer to "Warum fehlt mein Zugang?". */
  public record MissingAccess(String responsible, String text) {}
}
