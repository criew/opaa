package io.opaa.connection.profile;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.ChangeRejection.Category;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner;
import io.opaa.connection.token.SecretOwner.LibraryOwned;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceChangeGate;
import io.opaa.indexing.source.SourceChangeGate.Answers;
import io.opaa.indexing.source.SourceChangeGate.Transition;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceTargetRefusedException;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A library's move between frames - its profile changed, connected, released - through its
 * connector: the effective configuration before and after passes {@link SourceChangeGate}, all
 * moves of a change are checked before anything is written, and a written move discards the run
 * state and leaves the audit entry a direct change of the library would.
 */
@Component
public class SourceTransitions {

  private final EffectiveSourceSettings effective;
  private final ConnectionSecrets secrets;
  private final SourceConnectorRegistry connectors;
  private final SourceChangeGate gate;
  private final AuditEventRecorder audit;

  public SourceTransitions(
      EffectiveSourceSettings effective,
      ConnectionSecrets secrets,
      SourceConnectorRegistry connectors,
      AuditEventRecorder audit) {
    this.effective = effective;
    this.secrets = secrets;
    this.connectors = connectors;
    this.gate = new SourceChangeGate(connectors);
    this.audit = audit;
  }

  /**
   * The libraries among {@code libraries} that hold a stored secret of their own (the column), with
   * one query - never by whether it can be decrypted now, and never a person's shared secret, which
   * no move of a single library discards.
   */
  public Set<UUID> holdingSecrets(Collection<KnowledgeLibrary> libraries, UUID profileId) {
    Map<SecretOwner, UUID> owners = new HashMap<>();
    for (KnowledgeLibrary library : libraries) {
      owners.put(new LibraryOwned(library.getId()), library.getId());
    }
    Set<SecretOwner> notHolding = secrets.statesAmong(owners.keySet()).keySet();
    Set<UUID> holding = new HashSet<>();
    owners.forEach(
        (owner, id) -> {
          if (!notHolding.contains(owner)) {
            holding.add(id);
          }
        });
    return holding;
  }

  /**
   * Whether a person's secret issued under {@code from} stands for another target under {@code to}
   * - a changed default that binds the credentials, such as a share.
   */
  public boolean rebindsPersons(ConnectionProfile from, ConnectionProfile to) {
    return !Objects.equals(effective.personTarget(from), effective.personTarget(to));
  }

  /**
   * {@code library} moving from {@code from} to {@code to} - empty for its own address, a profile
   * possibly not saved yet - at {@code address}. Its secret goes along only while {@code to} takes
   * one and its {@link SecretTarget} stays, and never when {@code discardSecret}; {@code
   * holdsSecret} says whether its column holds one. A secret is read only for a changed
   * configuration.
   */
  public Move move(
      KnowledgeLibrary library,
      Optional<ConnectionProfile> from,
      Optional<ConnectionProfile> to,
      String address,
      boolean discardSecret,
      boolean holdsSecret) {
    SourceConnector connector = connectors.connector(library.getSourceType());
    SourceSettings before = effective.framed(library, from, library.getSourceUrl(), null);
    ConnectorData kept = effective.keptDefaults(from, to);
    SourceSettings after = effective.framed(library, to, address, null, kept);
    boolean takesSecret = to.map(SourceTransitions::takesSecret).orElse(true);
    boolean sameTarget =
        SecretTarget.of(connector, before).admits(SecretTarget.of(connector, after));
    boolean keepsSecret = !discardSecret && takesSecret && sameTarget;
    if (!before.equals(after)) {
      Secret held = effective.heldSecret(library, from);
      before = before.withCredentials(held);
      after = keepsSecret ? after.withCredentials(held) : after;
    }
    // a frame without the library's own secret leaves an unused one to adoptFrame, not to the move
    boolean discards = holdsSecret && (discardSecret || takesSecret && !sameTarget);
    return new Move(library, before, after, discards, takesSecret, kept);
  }

  /**
   * Writes what {@code move} keeps of the defaults its old frame set and its new one does not into
   * the library's own settings - part of writing the move, before {@link #applied}.
   */
  public void keepDefaults(Move move) {
    effective.keepDefaults(move.library(), move.keptDefaults());
  }

  /**
   * {@code library} released from {@code from} to its own address: what the profile set becomes its
   * own ({@link EffectiveSourceSettings#releaseFrame}), so the effective configuration stays and no
   * connector is asked.
   */
  public Move release(KnowledgeLibrary library, Optional<ConnectionProfile> from) {
    SourceSettings before = effective.framed(library, from, library.getSourceUrl(), null);
    return new Move(library, before, before, false, true, null);
  }

  /**
   * The connectors' refusals of {@code moves}, empty when all pass; nothing is written. A move that
   * leaves the effective configuration as it is asks no connector.
   */
  public List<ChangeRejection> check(List<Move> moves, Answers answers) {
    List<ChangeRejection> rejections = new ArrayList<>();
    for (Move move : moves) {
      if (!move.changesConfiguration()) {
        continue;
      }
      try {
        gate.validate(
            new Transition(move.library(), move.before(), move.after(), move.replacesConnection()),
            answers);
      } catch (ValidationException e) {
        rejections.add(
            new ChangeRejection(
                move.library().getId(),
                e instanceof SourceTargetRefusedException ? Category.CONNECTION : Category.SETTINGS,
                e.getMessage()));
      }
    }
    return rejections;
  }

  /**
   * {@link #check} for one move, refused with the connector's own message.
   *
   * @throws ValidationException (German 400) when the connector refuses it
   */
  public void require(Move move) {
    List<ChangeRejection> rejections = check(List.of(move), new Answers());
    if (!rejections.isEmpty()) {
      throw new ValidationException(rejections.getFirst().message());
    }
  }

  /**
   * After {@code move} is written: discards the run state it invalidates and returns the fields it
   * changed, as the audit of a direct change names them.
   */
  public Set<String> applied(Move move) {
    Set<String> changed = new LinkedHashSet<>();
    SourceSettings before = move.before();
    SourceSettings after = move.after();
    if (!Objects.equals(before.sourceUrl(), after.sourceUrl())) {
      changed.add("sourceUrl");
    }
    if (!Objects.equals(before.sourceProxy(), after.sourceProxy())) {
      changed.add("sourceProxy");
    }
    if (before.sourceInsecureSsl() != after.sourceInsecureSsl()) {
      changed.add("sourceInsecureSsl");
    }
    if (move.discardsSecret()) {
      changed.add("sourceCredentials");
    }
    if (move.changesConfiguration()) {
      changed.addAll(gate.applied(move.library(), before, after));
    }
    return changed;
  }

  /** The audit entry of a library {@code caller} changed through its profile. */
  public void record(CurrentUser caller, KnowledgeLibrary library, Set<String> changed) {
    if (changed.isEmpty()) {
      return;
    }
    List<String> fields = List.copyOf(changed);
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(library.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.LIBRARY_SOURCE_UPDATED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.auditName())
            .before(Map.of("changedFields", fields))
            .after(Map.of("changedFields", fields))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private static boolean takesSecret(ConnectionProfile profile) {
    return profile.getAuthMethod() == ConnectionAuthMethod.PERSONAL_SECRET;
  }

  /**
   * One library's planned move: the effective configuration before and after, the secret in {@code
   * after} only where it goes along.
   *
   * @param discardsSecret whether the secret its column holds is to be discarded
   * @param takesSecret whether the frame after signs in with the library's own secret
   * @param keptDefaults the defaults of the frame before that the frame after no longer sets, which
   *     the library keeps as its own; {@code null} for none
   */
  public record Move(
      KnowledgeLibrary library,
      SourceSettings before,
      SourceSettings after,
      boolean discardsSecret,
      boolean takesSecret,
      ConnectorData keptDefaults) {

    /** Whether address, transport or connector settings change; the secret does not count. */
    public boolean changesConfiguration() {
      return !before.withoutCredentials().equals(after.withoutCredentials());
    }

    /**
     * Whether the connector checks the connection anew: address or transport change, and the
     * library still has what it signs in with. Otherwise it checks its settings only.
     */
    boolean replacesConnection() {
      boolean connectionChanged =
          !Objects.equals(before.sourceUrl(), after.sourceUrl())
              || !Objects.equals(before.sourceProxy(), after.sourceProxy())
              || before.sourceInsecureSsl() != after.sourceInsecureSsl();
      return connectionChanged && (after.sourceCredentials() != null || !takesSecret);
    }
  }
}
