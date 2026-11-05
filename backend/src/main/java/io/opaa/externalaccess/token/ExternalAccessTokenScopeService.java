package io.opaa.externalaccess.token;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
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
 * of the installation - for a token of a service account consent to its account ∩ token ∩ account ∩
 * switch (Entscheidung 7). Four factors, four collaborators, one intersection - none of them is
 * copied into the token, so withdrawing any one of them takes the library out of the next call's
 * view without anyone touching the token.
 *
 * <p>Two of the four are seams into neighbouring issues: {@link
 * ExternalAccessSettingsService#isEnabled()} and {@link ExternalAccessLibraryRelease}. Adding them
 * here rather than in front of the search means there is exactly one place the intersection is
 * formed - a second filtering site would be a second truth.
 *
 * <p>A library whose release is gone is not merely filtered out: its selection entry is
 * extinguished, so a later release does not revive it. That is the one write on this read path, and
 * it happens at most once per entry.
 */
@Service
public class ExternalAccessTokenScopeService {

  private final ExternalAccessTokenRepository tokens;
  private final ServiceAccountTokenRepository serviceAccountTokens;
  private final ServiceAccountRepository serviceAccounts;
  private final ServiceAccountLibraryConsentRepository consents;
  private final KnowledgeLibraryRepository libraries;
  private final UserRepository users;
  private final LibraryAccessService libraryAccess;
  private final ExternalAccessSettingsService settings;
  private final ExternalAccessLibraryRelease release;
  private final Clock clock;

  public ExternalAccessTokenScopeService(
      ExternalAccessTokenRepository tokens,
      ServiceAccountTokenRepository serviceAccountTokens,
      ServiceAccountRepository serviceAccounts,
      ServiceAccountLibraryConsentRepository consents,
      KnowledgeLibraryRepository libraries,
      UserRepository users,
      LibraryAccessService libraryAccess,
      ExternalAccessSettingsService settings,
      ExternalAccessLibraryRelease release,
      Clock clock) {
    this.tokens = tokens;
    this.serviceAccountTokens = serviceAccountTokens;
    this.serviceAccounts = serviceAccounts;
    this.consents = consents;
    this.libraries = libraries;
    this.users = users;
    this.libraryAccess = libraryAccess;
    this.settings = settings;
    this.release = release;
    this.clock = clock;
  }

  /**
   * The libraries this token may be used against right now. Empty when the channel is closed, the
   * token is no longer active or its person is gone - the caller then learns nothing about which of
   * the three it was, the same way a missing chunk is never explained in the web interface.
   *
   * <p>Takes the token id and nothing else: person <em>and</em> organisation are read from the
   * token's own row, so no caller can widen or narrow the intersection by passing a different one.
   */
  @Transactional
  public Set<UUID> effectiveLibraryIds(UUID tokenId) {
    if (!settings.isEnabled()) {
      return Set.of();
    }
    ExternalAccessToken token = tokens.findById(tokenId).orElse(null);
    if (token == null) {
      return serviceAccountView(tokenId);
    }
    User owner = users.findById(token.getUserId()).orElse(null);
    if (owner == null) {
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
    Set<UUID> readable = libraryAccess.readableLibraryIds(owner.getId(), owner.getOrganizationId());
    Set<UUID> stillReleased = release.releasedAmong(selected);
    if (token.extinguishAllBut(stillReleased, now)) {
      tokens.save(token);
    }
    Set<UUID> effective = new LinkedHashSet<>(selected);
    effective.retainAll(readable);
    effective.retainAll(stillReleased);
    return effective;
  }

  /**
   * The view of a service-account token (ADR-0035, Entscheidung 7): the libraries consented to its
   * account ∩ token active ∩ account active, inside the account's organization. The
   * Fremdzugangsfreigabe of a library does not count here, and no person's rights do either - the
   * consent of the library administration is the one reach a Fachverfahren has.
   */
  private Set<UUID> serviceAccountView(UUID tokenId) {
    ServiceAccountToken token = serviceAccountTokens.findById(tokenId).orElse(null);
    Instant now = clock.instant();
    if (token == null || !token.isActive(now)) {
      return Set.of();
    }
    ServiceAccount account = serviceAccounts.findById(token.getServiceAccountId()).orElse(null);
    if (account == null || !account.isActive()) {
      return Set.of();
    }
    Set<UUID> consented = consents.findLibraryIdsInForce(account.getId(), now);
    if (consented.isEmpty()) {
      return Set.of();
    }
    Set<UUID> effective = new LinkedHashSet<>();
    for (KnowledgeLibrary library : libraries.findAllById(consented)) {
      if (account.getOrganizationId().equals(library.getOrganizationId())
          && !library.isOwnerOnly()) {
        effective.add(library.getId());
      }
    }
    return effective;
  }
}
