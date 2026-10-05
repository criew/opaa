package io.opaa.architecture.fixture.connectorprofiles.connection.profile;

import java.util.List;

/** Lists the MCP servers. */
public class McpServerProfileService {
  List<String> list(ConnectionProfileRepository profiles) {
    return profiles.findByKindOrderByNameAsc("MCP_SERVER");
  }
}
