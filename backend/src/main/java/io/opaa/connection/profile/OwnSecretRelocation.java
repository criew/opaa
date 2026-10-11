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
 * of its own, and the log names numbers only.
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
    int moved = relocate();
    if (moved > 0) {
      log.info("Moved the own secret of {} libraries on profiles into the token store", moved);
    }
  }

  /** Moves what is left in the columns; returns how many libraries moved. */
  int relocate() {
    int moved = 0;
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
        Boolean done = perLibrary.execute(status -> moveOne(libraryId, profile.getId()));
        if (Boolean.TRUE.equals(done)) {
          moved++;
        }
      }
    }
    return moved;
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
