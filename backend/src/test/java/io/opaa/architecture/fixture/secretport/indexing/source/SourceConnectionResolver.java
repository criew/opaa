package io.opaa.architecture.fixture.secretport.indexing.source;

public interface SourceConnectionResolver {
  String currentCredentials(Object library);
}
