package io.opaa.indexing.metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * What one model call did, collected while it is evaluated and written to the Zählwerk in one step
 * (metadata-schema.md, #1073). Mutable on purpose - it is filled by the single thread that made the
 * call and never shared.
 */
public final class ModelExtractionTally {

  private long acceptedValues;
  private long keywordsAssigned;
  private boolean failed;
  private final List<ModelExtractionRejection> rejections = new ArrayList<>();

  /** One discarded value, kept with its confidence so the distribution stays measurable. */
  public record ModelExtractionRejection(
      String fieldKey, String proposedValue, Double confidence, Reason reason) {}

  /** Why a proposed value was discarded; mirrors {@code metadata_model_rejections.reason}. */
  public enum Reason {
    BELOW_THRESHOLD,
    OUTSIDE_VOCABULARY
  }

  public void countAccepted() {
    acceptedValues++;
  }

  public void countKeywords(int count) {
    keywordsAssigned += count;
  }

  public void countFailure() {
    failed = true;
  }

  public void countRejection(String fieldKey, String proposedValue, Double confidence, Reason why) {
    rejections.add(new ModelExtractionRejection(fieldKey, proposedValue, confidence, why));
  }

  public long acceptedValues() {
    return acceptedValues;
  }

  public long keywordsAssigned() {
    return keywordsAssigned;
  }

  public boolean failed() {
    return failed;
  }

  public List<ModelExtractionRejection> rejections() {
    return List.copyOf(rejections);
  }

  public long rejectedBelowThreshold() {
    return countOf(Reason.BELOW_THRESHOLD);
  }

  public long rejectedOutsideVocabulary() {
    return countOf(Reason.OUTSIDE_VOCABULARY);
  }

  private long countOf(Reason reason) {
    return rejections.stream().filter(rejection -> rejection.reason() == reason).count();
  }
}
