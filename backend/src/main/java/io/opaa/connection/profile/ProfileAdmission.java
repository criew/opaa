package io.opaa.connection.profile;

import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.knowledge.SourceType;
import java.util.Optional;

/** Whether a profile admits a library of a type: same connector, profiles, library ownership. */
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
    ConnectionProfile found =
        profile.orElseThrow(() -> new NotFoundException("Zugang nicht gefunden"));
    if (!found.getSourceType().equals(type)) {
      throw new ValidationException("Der Zugang gehört zu einer anderen Quellart");
    }
    if (!descriptor.admitsProfiles()) {
      throw new ValidationException("Diese Quellart wird nicht über Zugänge verbunden");
    }
    if (!found.getOwnership().admitsLibraries()) {
      throw new ValidationException("Der Zugang ist nur für verbundene Konten von Personen");
    }
    return found;
  }
}
