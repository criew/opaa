package io.opaa.externalaccess.token;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The stand-in until #1731 delivers the release field: every library counts as released, so the
 * intersection of {@link ExternalAccessTokenScopeService} degenerates to "rights of the person and
 * selection of the token" and the remaining three factors stay testable. Removed when #1731 lands;
 * a second {@link ExternalAccessLibraryRelease} bean then fails the start-up loudly.
 *
 * <p>This is permissive, and it is only defensible because the channel reaches nothing yet: no path
 * is on {@code io.opaa.auth.ExternalAccessPathAllowlist} until #1720/#1721 register one, and the
 * installation switch of #1717 is off in the delivered settings.
 */
@Component
class DefaultExternalAccessLibraryRelease implements ExternalAccessLibraryRelease {

  @Override
  public Set<UUID> releasedAmong(Collection<UUID> libraryIds) {
    return Set.copyOf(libraryIds);
  }
}
