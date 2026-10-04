package io.opaa.architecture.fixture.secretport.query;

import io.opaa.architecture.fixture.secretport.indexing.source.SourceConnectionResolver;
import io.opaa.architecture.fixture.secretport.indexing.source.SourceStateLookup;

/** The answer path holds the secret port instead of only the read-only lookup. */
public class SourceAnswers {
  SourceConnectionResolver resolver;
  SourceStateLookup states;
}
