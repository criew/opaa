package io.opaa.connection.log;

import io.opaa.api.types.ConnectionEndCause;
import io.opaa.api.types.ConnectionLogEventType;
import io.opaa.audit.AuditActorPseudonymService;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of the connection log. Persons are written as their audit pseudonym, never by id.
 * Joins the caller's transaction: an event whose change rolls back leaves no entry.
 */
@Service
public class ConnectionLog {

  private final ConnectionLogRepository repository;
  private final AuditActorPseudonymService pseudonyms;

  ConnectionLog(ConnectionLogRepository repository, AuditActorPseudonymService pseudonyms) {
    this.repository = repository;
    this.pseudonyms = pseudonyms;
  }

  /**
   * Appends one entry. {@code personUserId} is whose connection it is, {@code profileName} the
   * profile's name now; {@code cause} is null for an event that does not end a connection.
   */
  @Transactional
  public void record(
      UUID organizationId,
      ConnectionLogEventType eventType,
      ConnectionLogActor actor,
      UUID personUserId,
      UUID profileId,
      String profileName,
      ConnectionEndCause cause) {
    Objects.requireNonNull(organizationId, "organizationId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(personUserId, "personUserId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(profileName, "profileName");
    String actorRef =
        switch (Objects.requireNonNull(actor, "actor")) {
          case ConnectionLogActor.Person person -> pseudonymOf(person.userId(), organizationId);
          case ConnectionLogActor.SystemProcess ignored -> ConnectionLogActor.SYSTEM_LABEL;
        };
    repository.save(
        new ConnectionLogEntry(
            organizationId,
            Instant.now(),
            eventType,
            actorRef,
            pseudonymOf(personUserId, organizationId),
            profileId,
            profileName,
            cause));
  }

  private String pseudonymOf(UUID userId, UUID organizationId) {
    return pseudonyms.pseudonymFor(userId, organizationId).toString();
  }
}
