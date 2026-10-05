package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.SourceDraft.DraftOwner;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.knowledge.SourceType;
import java.util.Optional;

/**
 * Whether a profile admits a connection of a type: same connector, profiles, and the owner - a
 * library, or a person where the profile's ownership and its connector's sign-in both name persons.
 */
public final class ProfileAdmission {

  private ProfileAdmission() {}

  /**
   * {@code profile} once it admits a library of {@code type}.
   *
   * @throws NotFoundException for a missing profile
   * @throws ValidationException (German 400) for a profile that does not admit it
   */
  public static ConnectionProfile require(
      Optional<ConnectionProfile> profile, SourceType type, SourceConnectorDescriptor descriptor) {
    return require(profile, type, descriptor, DraftOwner.LIBRARY);
  }

  /**
   * {@code profile} once it admits a connection of {@code type} owned by {@code owner}.
   *
   * @throws NotFoundException for a missing profile
   * @throws ValidationException (German 400) for a profile that does not admit it
   */
  public static ConnectionProfile require(
      Optional<ConnectionProfile> profile,
      SourceType type,
      SourceConnectorDescriptor descriptor,
      DraftOwner owner) {
    ConnectionProfile found =
        profile.orElseThrow(() -> new NotFoundException("Zugang nicht gefunden"));
    if (found.isMcpServer() || !found.getSourceType().equals(type)) {
      throw new ValidationException("Der Zugang gehört zu einer anderen Quellart");
    }
    if (!descriptor.admitsProfiles()) {
      throw new ValidationException("Diese Quellart wird nicht über Zugänge verbunden");
    }
    switch (owner) {
      case LIBRARY -> {
        if (!found.getOwnership().admitsLibraries()) {
          throw new ValidationException("Der Zugang ist nur für verbundene Konten von Personen");
        }
      }
      case PERSON -> {
        if (!admitsPersons(found, descriptor)) {
          throw new ValidationException("Der Zugang ist nicht für verbundene Konten von Personen");
        }
      }
    }
    return found;
  }

  /** Whether {@code profile} and its connector's sign-in both admit a person's connection. */
  public static boolean admitsPersons(
      ConnectionProfile profile, SourceConnectorDescriptor descriptor) {
    return profile.getOwnership().admitsPersons()
        && descriptor
            .profileDeclaration()
            .signIn(profile.getAuthMethod())
            .map(SignIn::owners)
            .filter(owners -> owners.contains(ConnectionOwnership.PERSON))
            .isPresent();
  }
}
