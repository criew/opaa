package io.opaa.architecture.fixture.librarysecret.library;

import io.opaa.architecture.fixture.librarysecret.connection.profile.ConnectionSecrets;
import io.opaa.architecture.fixture.librarysecret.knowledge.KnowledgeLibrary;

/** Asks the secret store directly instead of the port. */
public class SecretShortcut {
  String secret(ConnectionSecrets secrets, KnowledgeLibrary library) {
    return secrets.current(library);
  }
}
