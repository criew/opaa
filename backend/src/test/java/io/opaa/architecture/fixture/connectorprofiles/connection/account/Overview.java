package io.opaa.architecture.fixture.connectorprofiles.connection.account;

import io.opaa.architecture.fixture.connectorprofiles.connection.profile.ConnectionProfileRepository;
import java.util.List;

/** Lists the connector profiles and looks one up by id. */
public class Overview {
  List<String> connectable(ConnectionProfileRepository profiles) {
    profiles.findById("p");
    return profiles.findConnectorsByName();
  }
}
