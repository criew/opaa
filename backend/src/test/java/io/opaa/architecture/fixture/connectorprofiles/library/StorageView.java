package io.opaa.architecture.fixture.connectorprofiles.library;

import io.opaa.architecture.fixture.connectorprofiles.connection.profile.ConnectionProfileRepository;
import java.util.List;

/** library lists profiles of a kind it names itself. */
public class StorageView {
  List<String> profiles(ConnectionProfileRepository profiles) {
    return profiles.findByKindOrderByNameAsc("CONNECTOR");
  }
}
