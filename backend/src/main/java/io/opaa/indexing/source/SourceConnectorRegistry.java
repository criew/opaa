package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every {@link SourceConnector} by the source type it serves - the one way the administration
 * reaches a connector. Built from the connector beans; startup fails unless every {@link
 * DocumentSourceType} has exactly one connector whose {@link SourceConnectorDescriptor#indexingRun}
 * agrees with {@link DocumentSourceType#hasIndexingRun}, and a connector names a push intake
 * exactly when it is a {@link PushIntakeHandler}.
 */
public class SourceConnectorRegistry {

  private final Map<DocumentSourceType, SourceConnector> connectors =
      new EnumMap<>(DocumentSourceType.class);

  public SourceConnectorRegistry(List<SourceConnector> connectorBeans) {
    for (SourceConnector connector : connectorBeans) {
      SourceConnectorDescriptor descriptor = connector.descriptor();
      DocumentSourceType type = descriptor.type();
      if (connectors.put(type, connector) != null) {
        throw new IllegalStateException("More than one SourceConnector serves source type " + type);
      }
      if (descriptor.indexingRun() != type.hasIndexingRun()) {
        throw new IllegalStateException(
            "SourceConnector for " + type + " disagrees with hasIndexingRun()");
      }
      if ((descriptor.pushIntake() != null) != (connector instanceof PushIntakeHandler)) {
        throw new IllegalStateException(
            "SourceConnector for " + type + " must name a push intake exactly when it handles one");
      }
    }
    Set<DocumentSourceType> missing = EnumSet.allOf(DocumentSourceType.class);
    missing.removeAll(connectors.keySet());
    if (!missing.isEmpty()) {
      throw new IllegalStateException("No SourceConnector bean is registered for " + missing);
    }
  }

  /** The connector serving {@code type}. Always succeeds after construction. */
  public SourceConnector connector(DocumentSourceType type) {
    return connectors.get(type);
  }

  public SourceConnectorDescriptor descriptor(DocumentSourceType type) {
    return connector(type).descriptor();
  }

  /**
   * The handler behind the push intake of {@code type}.
   *
   * @throws IllegalStateException when that connector offers none
   */
  public PushIntakeHandler pushIntakeHandler(DocumentSourceType type) {
    if (!(connector(type) instanceof PushIntakeHandler handler)) {
      throw new IllegalStateException("The SourceConnector for " + type + " offers no push intake");
    }
    return handler;
  }

  /** How originals of {@code type} are served; empty for a type without an original to serve. */
  public Optional<OriginalAccess> originalAccess(DocumentSourceType type) {
    return connector(type) instanceof OriginalAccess access
        ? Optional.of(access)
        : Optional.empty();
  }

  /**
   * The listing {@code type} offers before its configuration is saved.
   *
   * @throws IllegalStateException when that connector offers none
   */
  public SourceBrowser browser(DocumentSourceType type) {
    if (!(connector(type) instanceof SourceBrowser browser)) {
      throw new IllegalStateException("The SourceConnector for " + type + " offers no listing");
    }
    return browser;
  }
}
