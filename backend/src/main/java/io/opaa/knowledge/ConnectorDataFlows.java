package io.opaa.knowledge;

import java.util.Map;
import java.util.Optional;

/**
 * The outbound data flow of the compliance manifest each connector type opens, for the disclosure
 * of the data collection. Each connector names its own in its descriptor, the connector registry
 * answers for all; a type without one ({@code FILESYSTEM}, {@code UPLOAD}) reads only from the
 * server itself or from uploads.
 */
public interface ConnectorDataFlows {

  /** The data flow of every registered connector type that sends data out, by type. */
  Map<SourceType, String> byType();

  /** The data flow of {@code type}; empty for a type that sends nothing out. */
  default Optional<String> of(SourceType type) {
    return Optional.ofNullable(byType().get(type));
  }
}
