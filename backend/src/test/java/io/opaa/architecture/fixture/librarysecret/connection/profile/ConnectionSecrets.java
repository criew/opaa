package io.opaa.architecture.fixture.librarysecret.connection.profile;

import io.opaa.architecture.fixture.librarysecret.knowledge.KnowledgeLibrary;

/** The store may read the secret. */
public class ConnectionSecrets {
  String current(KnowledgeLibrary library) {
    return library.getSourceCredentials();
  }
}
