package io.opaa.architecture.fixture.secretport.indexing.source;

public interface SourceStateLookup {
  boolean frozen(Object library);
}
