package io.opaa.architecture.fixture.secretport.indexing.credentials;

public interface SourceConnectionResolver {
  String currentCredentials(Object library);
}
