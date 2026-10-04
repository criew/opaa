package io.opaa.connection.profile;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.asset.AssetOwnerNames;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.ConnectorLockService.TypeState;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The profile requirement of a connector type ("Nur über Zugänge"): switching it on and off, what
 * that affects, and the address a library with its own address keeps meanwhile. Whether such a
 * library is locked decides {@link SourceBlocks}; switching is a governance event.
 */
@Service
@Transactional(readOnly = true)
public class ProfileRequirementService {

  /** The {@code code} of the {@code 400} for an own address where only a profile is admitted. */
  public static final String PROFILE_REQUIRED = "PROFILE_REQUIRED";

  /** The {@code code} of the {@code 409} when no profile could take over the type. */
  public static final String NEEDS_PROFILE = "PROFILE_REQUIREMENT_NEEDS_PROFILE";

  private final ConnectorTypePolicyRepository policies;
  private final ConnectionProfileRepository profiles;
  private final LibraryConnectionRepository connections;
  private final ProfileRequirements requirements;
  private final ConnectorLockService locks;
  private final AssetOwnerNames ownerNames;

  /**
   * Looked up per call: the core's port depends on this service's neighbours, and the registry's
   * connectors depend on the core.
   */
  private final ObjectProvider<SourceConnectorRegistry> connectors;

  private final AuditEventRecorder audit;
  private final Clock clock;

  public ProfileRequirementService(
      ConnectorTypePolicyRepository policies,
      ConnectionProfileRepository profiles,
      LibraryConnectionRepository connections,
      ProfileRequirements requirements,
      ConnectorLockService locks,
      AssetOwnerNames ownerNames,
      ObjectProvider<SourceConnectorRegistry> connectors,
      AuditEventRecorder audit,
      Clock clock) {
    this.policies = policies;
    this.profiles = profiles;
    this.connections = connections;
    this.requirements = requirements;
    this.locks = locks;
    this.ownerNames = ownerNames;
    this.connectors = connectors;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * The requirement of {@code type}, whether it can be switched, and whom switching it on affects.
   */
  public Overview overview(SourceType type) {
    TypeState state = locks.typeState(type);
    List<KnowledgeLibrary> ownAddress = connections.findLibrariesWithOwnAddress(type);
    Map<UUID, String> names = ownerNames.of(ownAddress);
    return new Overview(
        state,
        notSwitchableReason(state),
        ownAddress.stream()
            .map(
                library ->
                    new OwnAddressLibrary(
                        library.getId(),
                        library.getName(),
                        library.getOwnerType(),
                        names.get(library.getOwnerId())))
            .toList(),
        connectors.getObject().connector(type).profileRequirementGap());
  }

  /**
   * Switches the requirement of {@code type} on with {@code stock}, or off with {@code required}
   * false and no stock. Switching on needs a profile that could take over (409 {@value
   * #NEEDS_PROFILE}); a type that does not declare profiles as optional cannot be switched (400).
   */
  @Transactional
  public TypeState require(
      CurrentUser caller, SourceType type, boolean required, OwnAddressStock stock) {
    SourceConnectorDescriptor descriptor = locks.requireLockable(type);
    requireSwitchable(descriptor);
    if (required && stock == null) {
      throw new ValidationException("ownAddressStock ist beim Einschalten erforderlich");
    }
    if (!required && stock != null) {
      throw new ValidationException("ownAddressStock gehört nur zum Einschalten");
    }
    Instant now = clock.instant();
    ConnectorTypePolicy policy =
        policies.findById(type.key()).orElseGet(() -> new ConnectorTypePolicy(type, now));
    OwnAddressStock before = policy.getOwnAddressStock();
    if (required && before == null && !hasTakeover(type)) {
      throw new ConflictException(
          "Die Pflicht lässt sich erst einschalten, wenn es einen nicht gesperrten Zugang für"
              + " die Quellart „"
              + descriptor.displayName()
              + "“ gibt, der Bibliotheken zulässt.",
          NEEDS_PROFILE);
    }
    if (!Objects.equals(before, stock)) {
      policy.requireProfiles(stock, now);
      policies.save(policy);
      record(caller, type, descriptor.displayName(), before, stock);
    }
    return locks.typeState(type);
  }

  /**
   * Refuses a changed address of a library with its own address while its type is usable only
   * through a profile: a new address goes through a profile ({@code 400} {@value
   * #PROFILE_REQUIRED}). A connected library is not judged here.
   */
  public void requireOwnAddressKept(KnowledgeLibrary library, String requestedUrl) {
    if (!Objects.equals(library.getSourceUrl(), requestedUrl)
        && requirements.profileRequired(library.getSourceType())) {
      throw new ValidationException(
          "Die Quellart „"
              + displayName(library.getSourceType())
              + "“ ist nur noch über Zugänge nutzbar. Eine neue Adresse erhält die Bibliothek nur"
              + " über einen Zugang.",
          PROFILE_REQUIRED);
    }
  }

  /** The refusal of an own address for {@code type}, {@code 400} {@value #PROFILE_REQUIRED}. */
  public ValidationException ownAddressRefused(SourceType type) {
    return new ValidationException(
        "Die Quellart „"
            + displayName(type)
            + "“ ist nur über einen Zugang nutzbar. Wählen Sie einen Zugang; eine eigene Adresse"
            + " ist nicht möglich.",
        PROFILE_REQUIRED);
  }

  /**
   * Whether {@code profile} is the last one that could take over its type while the type is usable
   * only through a profile; deleting or locking it stays possible.
   */
  public boolean lastForRequirement(ConnectionProfile profile) {
    SourceType type = profile.getSourceType();
    return requirements.profileRequired(type)
        && takesOver(profile)
        && profiles.findBySourceTypeOrderByNameAsc(type).stream()
            .filter(this::takesOver)
            .allMatch(other -> other.getId().equals(profile.getId()));
  }

  private boolean hasTakeover(SourceType type) {
    return profiles.findBySourceTypeOrderByNameAsc(type).stream().anyMatch(this::takesOver);
  }

  /** A profile that could take over a type: not locked, and admitting libraries. */
  private boolean takesOver(ConnectionProfile profile) {
    return !profile.isLocked() && profile.getOwnership().admitsLibraries();
  }

  private void requireSwitchable(SourceConnectorDescriptor descriptor) {
    Optional.ofNullable(notSwitchable(descriptor.type(), descriptor.displayName()))
        .ifPresent(
            reason -> {
              throw new ValidationException(reason);
            });
  }

  private String notSwitchableReason(TypeState state) {
    String declared = notSwitchable(state.type(), state.displayName());
    if (declared != null || state.profileRequired()) {
      return declared;
    }
    return hasTakeover(state.type())
        ? null
        : "Es gibt noch keinen nicht gesperrten Zugang für diese Quellart, der Bibliotheken"
            + " zulässt. Zugänge legt die Systemverwaltung unter „Zugänge“ an.";
  }

  private String notSwitchable(SourceType type, String displayName) {
    return switch (requirements.declaredProfileSupport(type)) {
      case OPTIONAL -> null;
      case FORBIDDEN -> "Die Quellart „" + displayName + "“ wird nicht über Zugänge verbunden.";
      case REQUIRED -> "Die Quellart „" + displayName + "“ ist ohnehin nur über Zugänge nutzbar.";
    };
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
      SourceType type,
      String displayName,
      OwnAddressStock before,
      OwnAddressStock after) {
    Map<String, Object> beforeState = new LinkedHashMap<>();
    beforeState.put("sourceType", type.key());
    beforeState.put("profileRequired", before != null);
    if (before != null) {
      beforeState.put("ownAddressStock", before.name());
    }
    Map<String, Object> afterState = new LinkedHashMap<>();
    afterState.put("sourceType", type.key());
    afterState.put("profileRequired", after != null);
    if (after != null) {
      afterState.put("ownAddressStock", after.name());
    }
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(caller.organizationId())
            .actor(caller.id())
            .type(
                after == null
                    ? AuditEventType.CONNECTOR_PROFILE_OPTIONAL
                    : AuditEventType.CONNECTOR_PROFILE_REQUIRED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(
                    ("io.opaa.connection.type:" + type.key()).getBytes(StandardCharsets.UTF_8)),
                "Quellart " + displayName)
            .before(beforeState)
            .after(afterState)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /** {@link #overview}: why the requirement cannot be switched, {@code null} when it can. */
  public record Overview(
      TypeState state,
      String notSwitchableReason,
      List<OwnAddressLibrary> ownAddressLibraries,
      String coverageGap) {

    public boolean switchable() {
      return notSwitchableReason == null;
    }
  }

  /** A library of the type with its own address, and its owner's display name if it has one. */
  public record OwnAddressLibrary(
      UUID id, String name, AssetOwnerType ownerType, String ownerName) {}
}
