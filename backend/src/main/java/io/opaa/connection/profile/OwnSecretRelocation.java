package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves the own secret of every library on a profile that takes one from the library's column into
 * the store, at start: a library connected while its column held the secret runs on unchanged.
 * Idempotent - a library whose column is empty is left alone; each library moves in a transaction
 * of its own. A library that fails - such as a cleartext value while the encryption key is missing
 * - keeps its column and is tried again at the next start; it never stops the start. The log names
 * numbers and ids only.
 */
@Component
class OwnSecretRelocation {

  private static final Logger log = LoggerFactory.getLogger(OwnSecretRelocation.class);

  private final ConnectionProfileRepository profiles;
  private final LibraryConnectionRepository connections;
  private final KnowledgeLibraryRepository libraries;
  private final EffectiveSourceSettings effective;
  private final TransactionTemplate perLibrary;

  OwnSecretRelocation(
      ConnectionProfileRepository profiles,
      LibraryConnectionRepository connections,
      KnowledgeLibraryRepository libraries,
      EffectiveSourceSettings effective,
      PlatformTransactionManager transactionManager) {
    this.profiles = profiles;
    this.connections = connections;
    this.libraries = libraries;
    this.effective = effective;
    this.perLibrary = new TransactionTemplate(transactionManager);
  }

  @EventListener(ApplicationReadyEvent.class)
  void atStart() {
    try {
      Outcome outcome = relocate();
      if (outcome.moved() > 0 || outcome.failed() > 0) {
        log.info(
            "Moved the own secret of {} libraries on profiles into the token store, {} failed",
            outcome.moved(),
            outcome.failed());
      }
    } catch (RuntimeException e) {
      log.warn(
          "Moving the own secrets of libraries on profiles into the token store failed ({});"
              + " the next start tries again",
          e.getClass().getSimpleName());
    }
  }

  /** How many libraries moved and how many failed and kept their column. */
  record Outcome(int moved, int failed) {}

  /** Moves what is left in the columns, each library on its own. */
  Outcome relocate() {
    int moved = 0;
    int failed = 0;
    for (ConnectionProfile profile : profiles.findConnectorsByName()) {
      if (profile.getAuthMethod() != ConnectionAuthMethod.PERSONAL_SECRET) {
        continue;
      }
      List<UUID> onProfile =
          connections.findByProfileId(profile.getId()).stream()
              .map(LibraryConnection::getLibraryId)
              .toList();
      if (onProfile.isEmpty()) {
        continue;
      }
      Set<UUID> holding = libraries.findIdsHoldingSourceCredentials(onProfile);
      for (UUID libraryId : holding) {
        try {
          Boolean done = perLibrary.execute(status -> moveOne(libraryId, profile.getId()));
          if (Boolean.TRUE.equals(done)) {
            moved++;
          }
        } catch (RuntimeException e) {
          failed++;
          log.warn(
              "The own secret of library {} stays in its column ({}); the next start tries again",
              libraryId,
              e.getClass().getSimpleName());
        }
      }
    }
    return new Outcome(moved, failed);
  }

  /** Re-reads library, connection and profile in the transaction; any change since is skipped. */
  private boolean moveOne(UUID libraryId, UUID profileId) {
    boolean stillOnProfile =
        connections
            .findById(libraryId)
            .map(LibraryConnection::getProfileId)
            .filter(profileId::equals)
            .isPresent();
    ConnectionProfile profile = profiles.findById(profileId).orElse(null);
    if (!stillOnProfile
        || profile == null
        || profile.getAuthMethod() != ConnectionAuthMethod.PERSONAL_SECRET) {
      return false;
    }
    return libraries
        .findById(libraryId)
        .filter(library -> !library.isOwnerOnly())
        .map(
            library -> {
              effective.moveOwnSecretIntoStore(library, profile);
              return true;
            })
        .orElse(false);
  }
}
