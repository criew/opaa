package io.opaa.architecture.fixture.librarysecret.knowledge;

/** The core may read the secret. */
public class LibraryReader {
  String read(KnowledgeLibrary library) {
    return library.getSourceCredentials();
  }
}
