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
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceChangeGate;
import io.opaa.indexing.source.SourceChangeGate.Answers;
import io.opaa.indexing.source.SourceChangeGate.Transition;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
  private final SourceConnectorRegistry connectors;
  private final SourceChangeGate gate;
  private final AuditEventRecorder audit;

  public SourceTransitions(
      EffectiveSourceSettings effective,
      SourceConnectorRegistry connectors,
      AuditEventRecorder audit) {
    this.effective = effective;
    this.connectors = connectors;
    this.gate = new SourceChangeGate(connectors);
    this.audit = audit;
  }

  /**
   * {@code library} moving from {@code from} to {@code to} - empty for its own address, a profile
   * possibly not saved yet - at {@code address}. Its secret goes along only while {@code to} takes
   * one and its {@link SecretTarget} stays, and never when {@code discardSecret}.
   */
  public Move move(
      KnowledgeLibrary library,
      Optional<ConnectionProfile> from,
      Optional<ConnectionProfile> to,
      String address,
      boolean discardSecret) {
    SourceConnector connector = connectors.connector(library.getSourceType());
    Secret held = effective.heldSecret(library, from);
    SourceSettings before = effective.framed(library, from, library.getSourceUrl(), held);
    SourceSettings after = effective.framed(library, to, address, null);
    boolean takesSecret = to.map(SourceTransitions::takesSecret).orElse(true);
    boolean keepsSecret =
        !discardSecret
            && takesSecret
            && SecretTarget.of(connector, before).admits(SecretTarget.of(connector, after));
    return new Move(
        library,
        before,
        keepsSecret ? after.withCredentials(held) : after,
        !keepsSecret,
        takesSecret);
  }

  /**
   * {@code library} released from {@code from} to its own address: what the profile set becomes its
   * own ({@link EffectiveSourceSettings#releaseFrame}), so the effective configuration stays.
   */
  public Move release(KnowledgeLibrary library, Optional<ConnectionProfile> from) {
    SourceSettings before =
        effective.framed(
            library, from, library.getSourceUrl(), effective.heldSecret(library, from));
    return new Move(library, before, before, false, true);
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
                move.replacesConnection() ? Category.CONNECTION : Category.SETTINGS,
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
    if (before.sourceCredentials() != null && after.sourceCredentials() == null) {
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
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.getName())
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
   * @param discardsSecret whether the library's stored secret is to be discarded
   * @param takesSecret whether the frame after signs in with the library's own secret
   */
  public record Move(
      KnowledgeLibrary library,
      SourceSettings before,
      SourceSettings after,
      boolean discardsSecret,
      boolean takesSecret) {

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
