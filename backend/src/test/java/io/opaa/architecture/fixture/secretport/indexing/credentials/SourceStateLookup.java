package io.opaa.architecture.fixture.secretport.indexing.credentials;

public interface SourceStateLookup {
  boolean frozen(Object library);
}
