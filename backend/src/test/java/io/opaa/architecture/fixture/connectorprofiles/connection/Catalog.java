package io.opaa.architecture.fixture.connectorprofiles.connection;

import io.opaa.architecture.fixture.connectorprofiles.connection.profile.ConnectionProfileRepository;
import java.util.List;

/** A connector path listing every profile, MCP servers included. */
public class Catalog {
  List<String> scopes(ConnectionProfileRepository profiles) {
    return profiles.findAll();
  }
}
