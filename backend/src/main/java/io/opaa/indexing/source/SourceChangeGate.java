package io.opaa.indexing.source;

import io.opaa.common.ValidationException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The only way a change of a library's source reaches its connector: validated against the
 * effective configuration before, applied to the library's own part, and the run state the saved
 * change invalidates discarded. "Effective" is the library's own configuration merged with the
 * defaults of its profile, as {@link SourceConnectionResolver} resolves it.
 */
public class SourceChangeGate {

  private final SourceConnectorRegistry connectors;

  public SourceChangeGate(SourceConnectorRegistry connectors) {
    this.connectors = connectors;
  }

  /**
   * The change {@code requested} as {@code library}'s connector validates it against {@code
   * before}, the effective configuration until now; see {@link SourceConnector#validateChange}.
   */
  public SourceSettings validate(
      KnowledgeLibrary library,
      SourceSettings before,
      SourceSettings requested,
      boolean replacesConnection) {
    return connector(library).validateChange(before, requested, replacesConnection);
  }

  /**
   * {@link #validate} for {@code transition}, asking the connector only for a change {@code
   * answers} does not hold yet: libraries of one type sharing before, request and kind of change
   * are validated once, the connector's costly checks included. A refusal is kept and thrown again.
   *
   * @throws ValidationException the connector's refusal (German)
   */
  public SourceSettings validate(Transition transition, Answers answers) {
    KnowledgeLibrary library = transition.library();
    Answers.Key key =
        new Answers.Key(
            library.getSourceType(),
            transition.before(),
            transition.requested(),
            transition.replacesConnection());
    Answers.Answer answer = answers.known.get(key);
    if (answer == null) {
      try {
        answer =
            new Answers.Answer(
                validate(
                    library,
                    transition.before(),
                    transition.requested(),
                    transition.replacesConnection()),
                null);
      } catch (ValidationException e) {
        answer = new Answers.Answer(null, e);
      }
      answers.known.put(key, answer);
    }
    if (answer.refusal() != null) {
      throw answer.refusal();
    }
    return answer.validated();
  }

  /** Writes the connector settings of {@code validated} onto {@code library}'s own part. */
  public void apply(KnowledgeLibrary library, SourceSettings validated) {
    connector(library).applyChange(library, ConnectorData.storedIn(library), validated);
  }

  /**
   * Discards the run state the saved change from {@code before} to {@code after}, both effective,
   * invalidates, and returns the {@link SourceConnector#settingsState} keys that changed.
   */
  public Set<String> applied(
      KnowledgeLibrary library, SourceSettings before, SourceSettings after) {
    SourceConnector connector = connector(library);
    Map<String, Object> previous = connector.settingsState(library, before.connectorSettings());
    Map<String, Object> current = connector.settingsState(library, after.connectorSettings());
    Set<String> changed = new LinkedHashSet<>();
    for (Map.Entry<String, Object> entry : previous.entrySet()) {
      if (!Objects.equals(entry.getValue(), current.get(entry.getKey()))) {
        changed.add(entry.getKey());
      }
    }
    connector.onSourceChanged(
        library, !Objects.equals(before.sourceUrl(), after.sourceUrl()), changed);
    return changed;
  }

  /**
   * Discards once more the run state a saved change of {@code library} invalidated, with the keys
   * {@link #applied} returned for it - for a run that kept writing it meanwhile.
   */
  public void discardAgain(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    connector(library).onSourceChanged(library, addressChanged, changedSettings);
  }

  /**
   * Discards the run state a new address of {@code library} invalidates, its settings unchanged.
   */
  public void addressMoved(KnowledgeLibrary library) {
    connector(library).onSourceChanged(library, true, Set.of());
  }

  private SourceConnector connector(KnowledgeLibrary library) {
    return connectors.connector(library.getSourceType());
  }

  /**
   * One library's change from one effective configuration to another that nobody typed in - a
   * profile changed, the library connected or released - with the request the connector checks.
   */
  public record Transition(
      KnowledgeLibrary library,
      SourceSettings before,
      SourceSettings requested,
      boolean replacesConnection) {}

  /**
   * What the connectors answered so far; one instance spans the checks before a write and the write
   * itself, so nothing is asked twice. Holds secrets in memory for its short life only.
   */
  public static final class Answers {

    private final Map<Key, Answer> known = new HashMap<>();

    private record Key(
        SourceType type,
        SourceSettings before,
        SourceSettings requested,
        boolean replacesConnection) {}

    private record Answer(SourceSettings validated, ValidationException refusal) {}
  }
}
