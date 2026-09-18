package io.opaa.externalaccess.token;

import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.library.LibraryAccessService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The effective view of one token, computed <b>per call</b> and never frozen (ADR-0035,
 * Entscheidung 5): rights of the person ∩ release of the library ∩ selection of the token ∩ switch
 * of the installation. Four factors, four collaborators, one intersection - none of them is copied
 * into the token, so withdrawing any one of them takes the library out of the next call's view
 * without anyone touching the token.
 *
 * <p>Two of the four are seams into neighbouring issues: {@link
 * ExternalAccessSettingsService#isEnabled()} (#1717) and {@link ExternalAccessLibraryRelease}
 * (#1731). Adding them here rather than in front of the search means there is exactly one place the
 * intersection is formed - a second filtering site would be a second truth.
 *
 * <p>A library whose release is gone is not merely filtered out: its selection entry is
 * extinguished, so a later release does not revive it. That is the one write on this read path, and
 * it happens at most once per entry.
 */
@Service
public class ExternalAccessTokenScopeService {

  private final ExternalAccessTokenRepository tokens;
  private final LibraryAccessService libraryAccess;
  private final ExternalAccessSettingsService settings;
  private final ExternalAccessLibraryRelease release;
  private final Clock clock;

  public ExternalAccessTokenScopeService(
      ExternalAccessTokenRepository tokens,
      LibraryAccessService libraryAccess,
      ExternalAccessSettingsService settings,
      ExternalAccessLibraryRelease release,
      Clock clock) {
    this.tokens = tokens;
    this.libraryAccess = libraryAccess;
    this.settings = settings;
    this.release = release;
    this.clock = clock;
  }

  /**
   * The libraries this token may be used against right now. Empty when the channel is closed or the
   * token is no longer active - the caller then learns nothing about which of the two it was, the
   * same way a missing chunk is never explained in the web interface.
   */
  @Transactional
  public Set<UUID> effectiveLibraryIds(UUID tokenId, UUID organizationId) {
    ExternalAccessToken token = tokens.findById(tokenId).orElse(null);
    if (token == null || !settings.isEnabled()) {
      return Set.of();
    }
    Instant now = clock.instant();
    if (!token.isActive(now)) {
      return Set.of();
    }
    Set<UUID> selected = token.getLiveLibraryIds();
    if (selected.isEmpty()) {
      return Set.of();
    }
    Set<UUID> readable = libraryAccess.readableLibraryIds(token.getUserId(), organizationId);
    Set<UUID> stillReleased = release.releasedAmong(selected);
    if (token.extinguishAllBut(stillReleased, now)) {
      tokens.save(token);
    }
    Set<UUID> effective = new LinkedHashSet<>(selected);
    effective.retainAll(readable);
    effective.retainAll(stillReleased);
    return effective;
  }
}
