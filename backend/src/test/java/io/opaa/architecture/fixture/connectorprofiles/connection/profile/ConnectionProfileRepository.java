package io.opaa.architecture.fixture.connectorprofiles.connection.profile;

import java.util.List;

/** Lists across every kind, and the connector list built on them. */
public interface ConnectionProfileRepository {

  List<String> findAll();

  List<String> findByKindOrderByNameAsc(String kind);

  String findById(String id);

  default List<String> findConnectorsByName() {
    return findByKindOrderByNameAsc("CONNECTOR");
  }
}
