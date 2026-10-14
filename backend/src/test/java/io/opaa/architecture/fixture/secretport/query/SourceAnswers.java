package io.opaa.architecture.fixture.secretport.query;

import io.opaa.architecture.fixture.secretport.indexing.credentials.SourceConnectionResolver;
import io.opaa.architecture.fixture.secretport.indexing.credentials.SourceStateLookup;

/** The answer path holds the secret port instead of only the read-only lookup. */
public class SourceAnswers {
  SourceConnectionResolver resolver;
  SourceStateLookup states;
}
