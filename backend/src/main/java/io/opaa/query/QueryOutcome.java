package io.opaa.query;

import java.util.List;

/**
 * Domain counterpart of the generated {@code QueryMetadata} (#860 Teil 4) - mirrors its fluent
 * {@code withX}-style setters and bean getters for a low-friction test call site.
 */
public final class QueryOutcome {

  private final String model;
  private final int tokenCount;
  private final long durationMs;
  private boolean answeredWithoutKnowledge;
  private boolean noKnowledgeAvailableInSpace;
  private List<SearchedLibraryRef> searchedLibraries;

  public QueryOutcome(String model, int tokenCount, long durationMs) {
    this.model = model;
    this.tokenCount = tokenCount;
    this.durationMs = durationMs;
  }

  public QueryOutcome answeredWithoutKnowledge(boolean answeredWithoutKnowledge) {
    this.answeredWithoutKnowledge = answeredWithoutKnowledge;
    return this;
  }

  public QueryOutcome noKnowledgeAvailableInSpace(boolean noKnowledgeAvailableInSpace) {
    this.noKnowledgeAvailableInSpace = noKnowledgeAvailableInSpace;
    return this;
  }

  public QueryOutcome searchedLibraries(List<SearchedLibraryRef> searchedLibraries) {
    this.searchedLibraries = searchedLibraries;
    return this;
  }

  public String getModel() {
    return model;
  }

  public int getTokenCount() {
    return tokenCount;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public boolean getAnsweredWithoutKnowledge() {
    return answeredWithoutKnowledge;
  }

  public boolean getNoKnowledgeAvailableInSpace() {
    return noKnowledgeAvailableInSpace;
  }

  public List<SearchedLibraryRef> getSearchedLibraries() {
    return searchedLibraries;
  }
}
