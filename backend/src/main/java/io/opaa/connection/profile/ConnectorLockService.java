package io.opaa.connection.profile;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lock of a connector type and of a connection profile (spec "Konnektor-Freigabe und Sperre").
 * A locked type or profile admits no new library, and the port starts no run; the content stays
 * searchable and carries {@link #lockOf}. Lifting the lock lets the libraries run again as they
 * were. Locking and unlocking are governance events.
 */
@Service
@Transactional(readOnly = true)
public class ConnectorLockService {

  /** The {@code code} of the {@code 403} a creation on a locked type or profile produces. */
  public static final String CONNECTOR_LOCKED = "CONNECTOR_LOCKED";

  /** The {@code code} of the {@code 409} when an existing library's locked source is reached. */
  public static final String SOURCE_LOCKED = "SOURCE_LOCKED";

  private final ConnectorTypePolicyRepository policies;
  private final ConnectionProfileRepository profiles;
  private final SourceBlocks blocks;
  private final ProfileRequirements requirements;

  /**
   * Looked up per call: the core's port depends on this service, and the registry's connectors
   * depend on the core.
   */
  private final ObjectProvider<SourceConnectorRegistry> connectors;

  private final AuditEventRecorder audit;
  private final Clock clock;

  public ConnectorLockService(
      ConnectorTypePolicyRepository policies,
      ConnectionProfileRepository profiles,
      SourceBlocks blocks,
      ProfileRequirements requirements,
      ObjectProvider<SourceConnectorRegistry> connectors,
      AuditEventRecorder audit,
      Clock clock) {
    this.policies = policies;
    this.profiles = profiles;
    this.blocks = blocks;
    this.requirements = requirements;
    this.connectors = connectors;
    this.audit = audit;
    this.clock = clock;
  }

  /** Every connector type that reaches a source, by type key, with its lock. */
  public List<TypeState> typeStates() {
    Map<String, ConnectorTypePolicy> rows =
        policies.findAll().stream()
            .collect(Collectors.toMap(policy -> policy.getSourceType().key(), policy -> policy));
    return connectors.getObject().descriptors().stream()
        .filter(descriptor -> !descriptor.uploads())
        .map(descriptor -> typeState(descriptor, rows.get(descriptor.type().key())))
        .toList();
  }

  /** {@code type} with its lock and its profile requirement. */
  public TypeState typeState(SourceType type) {
    return typeState(requireLockable(type), policies.findById(type.key()).orElse(null));
  }

  public boolean isTypeLocked(SourceType type) {
    return policies.findById(type.key()).map(ConnectorTypePolicy::isLocked).orElse(false);
  }

  @Transactional
  public TypeState lockType(CurrentUser caller, SourceType type, boolean locked) {
    SourceConnectorDescriptor descriptor = requireLockable(type);
    Instant now = clock.instant();
    ConnectorTypePolicy policy =
        policies.findById(type.key()).orElseGet(() -> new ConnectorTypePolicy(type, now));
    if (policy.isLocked() != locked) {
      policy.lockedSince(locked ? now : null, now);
      policies.save(policy);
      record(
          caller,
          locked,
          UUID.nameUUIDFromBytes(
              ("io.opaa.connection.type:" + type.key()).getBytes(StandardCharsets.UTF_8)),
          "Quellart " + descriptor.displayName(),
          Map.of("sourceType", type.key()));
    }
    return typeState(descriptor, policy);
  }

  @Transactional
  public ConnectionProfile lockProfile(CurrentUser caller, UUID profileId, boolean locked) {
    ConnectionProfile profile =
        profiles
            .findById(profileId)
            .orElseThrow(() -> new NotFoundException("Zugang nicht gefunden"));
    if (profile.isLocked() != locked) {
      Instant now = clock.instant();
      profile.lockedSince(locked ? now : null, now);
      profiles.save(profile);
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("profile", profile.getName());
      after.put("sourceType", profile.getSourceType().key());
      record(caller, locked, profile.getId(), "Zugang " + profile.getName(), after);
    }
    return profile;
  }

  /**
   * Refuses a new library or connection when the type or {@code profile} ({@code null} for an own
   * address) is locked.
   */
  public void requireUnlocked(SourceType type, ConnectionProfile profile) {
    creationLock(type, profile)
        .ifPresent(
            notice -> {
              throw new AccessDeniedException(notice, CONNECTOR_LOCKED);
            });
  }

  /** Why no new library of {@code type} through {@code profile} is possible, empty if it is. */
  public Optional<String> creationLock(SourceType type, ConnectionProfile profile) {
    if (isTypeLocked(type)) {
      return Optional.of(
          "Die Quellart „"
              + displayName(type)
              + "“ ist gesperrt. Neue Bibliotheken sind erst wieder möglich, wenn die"
              + " Systemverwaltung die Sperre aufhebt.");
    }
    if (profile != null && profile.isLocked()) {
      return Optional.of(
          "Der Zugang „"
              + profile.getName()
              + "“ ist gesperrt. Neue Bibliotheken sind erst wieder möglich, wenn die"
              + " Systemverwaltung die Sperre aufhebt.");
    }
    return Optional.empty();
  }

  /** The lock a library carries, empty while none of the system administration's locks holds it. */
  public Optional<SourceBlock> lockOf(KnowledgeLibrary library) {
    return blocks.blockOf(library, SourceBlocks.LOCKS);
  }

  /** {@link #lockOf} for many libraries with three queries; a free library is absent. */
  public Map<UUID, SourceBlock> locksOf(Collection<KnowledgeLibrary> libraries) {
    return blocks.blocksAmong(libraries, SourceBlocks.LOCKS);
  }

  /** The descriptor of a type that reaches a source; refuses the upload and an unknown type. */
  SourceConnectorDescriptor requireLockable(SourceType type) {
    return connectors
        .getObject()
        .find(type)
        .map(connector -> connector.descriptor())
        .filter(found -> !found.uploads())
        .orElseThrow(
            () -> new ValidationException("Die Quellart " + type + " lässt sich nicht sperren"));
  }

  TypeState typeState(SourceConnectorDescriptor descriptor, ConnectorTypePolicy policy) {
    SourceType type = descriptor.type();
    boolean switchedOn = requirements.switchedOn(type, policy);
    return new TypeState(
        type,
        descriptor.displayName(),
        policy == null ? null : policy.getLockedAt(),
        requirements.declaredProfileSupport(type),
        requirements.effective(type, policy) == ConnectionProfileSupport.REQUIRED,
        switchedOn ? policy.getProfileRequiredAt() : null,
        switchedOn ? policy.getOwnAddressStock() : null);
  }

  private String displayName(SourceType type) {
    return connectors
        .getObject()
        .find(type)
        .map(connector -> connector.descriptor().displayName())
        .orElse(type.key());
  }

  private void record(
      CurrentUser caller,
      boolean locked,
      UUID objectId,
      String objectLabel,
      Map<String, Object> state) {
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(caller.organizationId())
            .actor(caller.id())
            .type(locked ? AuditEventType.CONNECTOR_LOCKED : AuditEventType.CONNECTOR_UNLOCKED)
            .object(AuditObjectType.SYSTEM_SETTING, objectId, objectLabel)
            .after(state)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /**
   * One connector type: since when it is locked, how its connector stands to profiles, whether it
   * is usable only through one now, and - while the system administration switched that on - since
   * when and what happens to its libraries with their own address.
   */
  public record TypeState(
      SourceType type,
      String displayName,
      Instant lockedAt,
      ConnectionProfileSupport profileSupport,
      boolean profileRequired,
      Instant profileRequiredAt,
      OwnAddressStock ownAddressStock) {

    public boolean locked() {
      return lockedAt != null;
    }
  }
}
