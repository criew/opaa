package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The only place that decides whether and why a library's source is blocked, and which reason wins:
 * a locked type, a locked profile, a removed profile, an address outside the profile, a missing
 * secret - in this order. A library without a connection is blocked only by a type lock. Callers
 * name the reasons they consider; the first of those that applies is the block.
 *
 * <p>Holds no transaction of its own, so a refusal does not mark a caller's transaction for
 * rollback.
 */
@Component
public class SourceBlocks {

  /** Every reason. */
  public static final Set<Reason> ALL = Collections.unmodifiableSet(EnumSet.allOf(Reason.class));

  /** The locks of the system administration. */
  public static final Set<Reason> LOCKS =
      Collections.unmodifiableSet(EnumSet.of(Reason.TYPE_LOCKED, Reason.PROFILE_LOCKED));

  /** What keeps the connection itself from being used, a lock aside. */
  public static final Set<Reason> CONNECTION =
      Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.copyOf(LOCKS)));

  static final String ADMINISTRATION = "Systemverwaltung";
  static final String LIBRARY_MANAGERS = "Verwaltende der Bibliothek";

  private static final String LOCKED = "Gesperrt – Inhalt wird nicht mehr aktualisiert.";
  private static final String LOCK_CONTENT_STAYS =
      "; der vorhandene Inhalt bleibt durchsuchbar. Zuständig ist die Systemverwaltung.";
  private static final String CONTENT_STAYS =
      " Der Inhalt bleibt durchsuchbar, wird aber nicht mehr aktualisiert.";

  private final ConnectorTypePolicyRepository policies;
  private final LibraryConnectionRepository connections;
  private final ConnectionProfileRepository profiles;

  /**
   * Looked up per call: the core's port depends on this class, and the registry's connectors depend
   * on the core.
   */
  private final ObjectProvider<SourceConnectorRegistry> connectors;

  public SourceBlocks(
      ConnectorTypePolicyRepository policies,
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      ObjectProvider<SourceConnectorRegistry> connectors) {
    this.policies = policies;
    this.connections = connections;
    this.profiles = profiles;
    this.connectors = connectors;
  }

  /** The first of the {@code considered} reasons that blocks {@code library}, empty if none. */
  public Optional<SourceBlock> blockOf(KnowledgeLibrary library, Set<Reason> considered) {
    return Optional.ofNullable(blocksAmong(List.of(library), considered).get(library.getId()));
  }

  /** {@link #blockOf} for many libraries with three queries; a library not blocked is absent. */
  public Map<UUID, SourceBlock> blocksAmong(
      Collection<KnowledgeLibrary> libraries, Set<Reason> considered) {
    Map<UUID, SourceBlock> blocks = new HashMap<>();
    for (Facts facts : factsOf(libraries)) {
      facts.firstBlock(considered).ifPresent(block -> blocks.put(facts.library().getId(), block));
    }
    return blocks;
  }

  /**
   * The profile {@code library} is connected through, empty for its own address, once none of the
   * {@code considered} reasons blocks it.
   *
   * @throws SourceConnectionBlockedException with the first of them that applies
   */
  public Optional<ConnectionProfile> requireUnblocked(
      KnowledgeLibrary library, Set<Reason> considered) {
    Facts facts = factsOf(List.of(library)).getFirst();
    facts
        .firstBlock(considered)
        .ifPresent(
            block -> {
              throw new SourceConnectionBlockedException(block);
            });
    return Optional.ofNullable(facts.profile());
  }

  private List<Facts> factsOf(Collection<KnowledgeLibrary> libraries) {
    if (libraries.isEmpty()) {
      return List.of();
    }
    Set<String> lockedTypes =
        policies.findAll().stream()
            .filter(ConnectorTypePolicy::isLocked)
            .map(policy -> policy.getSourceType().key())
            .collect(Collectors.toSet());
    Map<UUID, LibraryConnection> connectionOf =
        connections.findAllById(libraries.stream().map(KnowledgeLibrary::getId).toList()).stream()
            .collect(Collectors.toMap(LibraryConnection::getLibraryId, Function.identity()));
    Set<UUID> profileIds =
        connectionOf.values().stream()
            .map(LibraryConnection::getProfileId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    Map<UUID, ConnectionProfile> profileOf =
        profileIds.isEmpty()
            ? Map.of()
            : profiles.findAllById(profileIds).stream()
                .collect(Collectors.toMap(ConnectionProfile::getId, Function.identity()));
    List<Facts> facts = new ArrayList<>();
    for (KnowledgeLibrary library : libraries) {
      LibraryConnection connection = connectionOf.get(library.getId());
      ConnectionProfile profile =
          connection == null || connection.getProfileId() == null
              ? null
              : profileOf.get(connection.getProfileId());
      SourceType type = library.getSourceType();
      facts.add(
          new Facts(
              library,
              lockedTypes.contains(type.key()) ? typeLock(type) : null,
              connection != null,
              profile));
    }
    return facts;
  }

  private SourceBlock typeLock(SourceType type) {
    String displayName =
        connectors
            .getObject()
            .find(type)
            .map(connector -> connector.descriptor().displayName())
            .orElse(type.key());
    return new SourceBlock(
        Reason.TYPE_LOCKED,
        ADMINISTRATION,
        LOCKED
            + " Die Systemverwaltung hat die Quellart „"
            + displayName
            + "“ gesperrt"
            + LOCK_CONTENT_STAYS);
  }

  /** What decides the block of one library: its type lock, its connection and its profile. */
  private record Facts(
      KnowledgeLibrary library,
      SourceBlock typeLock,
      boolean connected,
      ConnectionProfile profile) {

    private Optional<SourceBlock> firstBlock(Set<Reason> considered) {
      return applicable().stream().filter(block -> considered.contains(block.reason())).findFirst();
    }

    /** Every block that applies, in order of precedence. */
    private List<SourceBlock> applicable() {
      List<SourceBlock> blocks = new ArrayList<>();
      if (typeLock != null) {
        blocks.add(typeLock);
      }
      if (!connected) {
        return blocks;
      }
      if (profile == null) {
        blocks.add(
            new SourceBlock(
                Reason.ACCESS_REMOVED,
                LIBRARY_MANAGERS,
                "Zugang entfernt: Der Zugang dieser Bibliothek wurde gelöscht. Die"
                    + " Verwaltenden der Bibliothek ordnen sie einem anderen Zugang zu."
                    + CONTENT_STAYS));
        return blocks;
      }
      if (profile.isLocked()) {
        blocks.add(
            new SourceBlock(
                Reason.PROFILE_LOCKED,
                ADMINISTRATION,
                LOCKED
                    + " Die Systemverwaltung hat den Zugang „"
                    + profile.getName()
                    + "“ gesperrt"
                    + LOCK_CONTENT_STAYS));
      }
      if (!ServerAddress.covers(profile.getServerUrl(), library.getSourceUrl())) {
        blocks.add(
            new SourceBlock(
                Reason.TARGET_OUTSIDE_PROFILE,
                LIBRARY_MANAGERS,
                "Die Adresse der Bibliothek liegt nicht unter der Server-Adresse des Zugangs \""
                    + profile.getName()
                    + "\". Die Verwaltenden der Bibliothek passen die Adresse an."
                    + CONTENT_STAYS));
      }
      notConnected().ifPresent(blocks::add);
      return blocks;
    }

    private Optional<SourceBlock> notConnected() {
      ConnectionAuthMethod method = profile.getAuthMethod();
      if (method == ConnectionAuthMethod.NONE) {
        return Optional.empty();
      }
      if (method == ConnectionAuthMethod.PERSONAL_SECRET) {
        return library.getSourceCredentials() != null
            ? Optional.empty()
            : Optional.of(
                new SourceBlock(
                    Reason.NOT_CONNECTED,
                    LIBRARY_MANAGERS,
                    "Verbindung getrennt: Für den Zugang \""
                        + profile.getName()
                        + "\" sind keine Zugangsdaten hinterlegt. Die Verwaltenden der"
                        + " Bibliothek tragen sie neu ein."
                        + CONTENT_STAYS));
      }
      // OAUTH, CLIENT_CREDENTIALS, SERVICE_ACCOUNT_KEY: no library can be connected with them yet
      return Optional.of(
          new SourceBlock(
              Reason.NOT_CONNECTED,
              ADMINISTRATION,
              "Nicht verbunden: Die Anmeldeart des Zugangs \""
                  + profile.getName()
                  + "\" wird für Bibliotheken noch nicht unterstützt. Zuständig ist die"
                  + " Systemverwaltung."
                  + CONTENT_STAYS));
    }
  }
}
