package io.opaa.architecture.fixture.librarysecret.library;

import io.opaa.architecture.fixture.librarysecret.knowledge.KnowledgeLibrary;
import java.util.function.Supplier;

/** Reads the secret by a call and by a method reference; the address is no secret. */
public class LibraryAdministration {
  boolean holds(KnowledgeLibrary library) {
    Supplier<String> secret = library::getSourceCredentials;
    return library.getSourceCredentials() != null && secret.get() != null
        || library.getSourceUrl() != null;
  }
}
