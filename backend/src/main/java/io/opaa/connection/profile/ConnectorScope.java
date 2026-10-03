package io.opaa.connection.profile;

import io.opaa.knowledge.SourceType;
import java.util.UUID;

/**
 * The scopes of {@code CREATE_CONNECTOR_LIBRARY} (ADR-0036, Nachtrag of 03.10.2026): {@code
 * TYPE:<key>} for a library with its own address, {@code PROFILE:<id>} for one through a profile.
 * connections is the only place that builds them.
 */
public final class ConnectorScope {

  private ConnectorScope() {}

  public static String ofType(SourceType type) {
    return "TYPE:" + type.key();
  }

  public static String ofProfile(UUID profileId) {
    return "PROFILE:" + profileId;
  }
}
