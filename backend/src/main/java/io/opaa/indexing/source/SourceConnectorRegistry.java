package io.opaa.indexing.source;

import io.opaa.common.ValidationException;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.SourceType;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every {@link SourceConnector} by the source type it serves - the one way the administration
 * reaches a connector, and the only list of source types there is (ADR-0038). Built from the
 * connector beans; startup fails when two connectors serve the same type, when no connector serves
 * {@link SourceType#UPLOAD} without a run, or when a connector names a push intake without handling
 * one or the other way round.
 */
public class SourceConnectorRegistry {

  private final Map<SourceType, SourceConnector> connectors = new HashMap<>();

  public SourceConnectorRegistry(List<SourceConnector> connectorBeans) {
    for (SourceConnector connector : connectorBeans) {
      SourceConnectorDescriptor descriptor = connector.descriptor();
      SourceType type = descriptor.type();
      if (connectors.put(type, connector) != null) {
        throw new IllegalStateException("More than one SourceConnector serves source type " + type);
      }
      if ((descriptor.pushIntake() != null) != (connector instanceof PushIntakeHandler)) {
        throw new IllegalStateException(
            "SourceConnector for " + type + " must name a push intake exactly when it handles one");
      }
    }
    SourceConnector upload = connectors.get(SourceType.UPLOAD);
    if (upload == null || upload.descriptor().indexingRun()) {
      throw new IllegalStateException("No SourceConnector serves UPLOAD without an indexing run");
    }
  }

  /** The connector serving {@code type}, empty for a type no connector serves. */
  public Optional<SourceConnector> find(SourceType type) {
    return Optional.ofNullable(type == null ? null : connectors.get(type));
  }

  /**
   * The connector serving {@code type}.
   *
   * @throws ValidationException (German 400) for a type no connector serves
   */
  public SourceConnector connector(SourceType type) {
    return find(type)
        .orElseThrow(() -> new ValidationException("sourceType " + type + " ist unbekannt"));
  }

  public SourceConnectorDescriptor descriptor(SourceType type) {
    return connector(type).descriptor();
  }

  /** Every registered connector's descriptor, ordered by type key. */
  public List<SourceConnectorDescriptor> descriptors() {
    return connectors.values().stream()
        .map(SourceConnector::descriptor)
        .sorted(Comparator.comparing(SourceConnectorDescriptor::type))
        .toList();
  }

  /** The handler behind the push intake of {@code type}, empty when that type offers none. */
  public Optional<PushIntakeHandler> pushIntakeHandler(SourceType type) {
    return find(type)
        .filter(PushIntakeHandler.class::isInstance)
        .map(PushIntakeHandler.class::cast);
  }

  /** How originals of {@code type} are served; empty for a type without an original to serve. */
  public Optional<OriginalAccess> originalAccess(SourceType type) {
    return find(type).filter(OriginalAccess.class::isInstance).map(OriginalAccess.class::cast);
  }

  /** The listing {@code type} offers before its configuration is saved, empty for none. */
  public Optional<SourceBrowser> browser(SourceType type) {
    return find(type).filter(SourceBrowser.class::isInstance).map(SourceBrowser.class::cast);
  }

  /**
   * Whether {@code document}'s {@code filePath} names a remote the connector run alone can read
   * again. A document of a type no connector serves any more counts as remote: nothing on this
   * machine may be read in its name.
   */
  public boolean isRemote(Document document) {
    if (document.getSourceType() == null) {
      return false;
    }
    return find(document.getSourceType()).map(c -> c.descriptor().remote()).orElse(true);
  }

  /**
   * The address a reader may open for {@code document}, {@code null} where its {@code filePath} is
   * a server-local path or an identity that opens nowhere. Deliberately visible to every reader: it
   * names one document's origin, not the library's source configuration.
   */
  public String deepLink(Document document) {
    boolean linkable =
        find(document.getSourceType()).map(c -> c.descriptor().deepLink()).orElse(false);
    return linkable ? document.getFilePath() : null;
  }
}
