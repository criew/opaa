package io.opaa.llm;

import java.util.List;

/**
 * The reference anchor of an embedding model (ADR-0061, Entscheidung 5): the vectors of fixed,
 * public probe texts. An endpoint passes as the same model when its vectors of these texts are
 * nearly those of the anchor, judged by the smallest cosine similarity. The check is against
 * mistakes; an endpoint that answers these texts on purpose like the model passes it.
 */
public final class EmbeddingAnchor {

  /** The probe texts, also the texts of every probe embedding. */
  public static final List<String> TEXTS =
      List.of(
          "OPAA Probeeinbettung",
          "Der Antrag auf Bewohnerparken wird beim Bürgerbüro gestellt.",
          "Haushaltsplan 2026, Einzelplan 03: Ansätze und Verpflichtungsermächtigungen");

  /** The smallest similarity at which an endpoint counts as the same model. */
  public static final double MIN_SIMILARITY = 0.99;

  private EmbeddingAnchor() {}

  /** The vectors one after the other, as {@code reference_vectors} stores them. */
  static float[] flatten(List<float[]> vectors) {
    int width = vectors.getFirst().length;
    float[] flat = new float[width * vectors.size()];
    for (int i = 0; i < vectors.size(); i++) {
      System.arraycopy(vectors.get(i), 0, flat, i * width, width);
    }
    return flat;
  }

  /**
   * The smallest cosine similarity between {@code vectors} and the stored {@code anchor}, or {@code
   * -1} when they cannot be compared: another count or another width.
   */
  static double smallestSimilarity(float[] anchor, List<float[]> vectors) {
    if (anchor == null || vectors.size() != TEXTS.size()) {
      return -1;
    }
    int width = vectors.getFirst().length;
    if (anchor.length != width * TEXTS.size()) {
      return -1;
    }
    double smallest = 1;
    for (int i = 0; i < vectors.size(); i++) {
      float[] vector = vectors.get(i);
      if (vector.length != width) {
        return -1;
      }
      smallest = Math.min(smallest, cosine(anchor, i * width, vector));
    }
    return smallest;
  }

  private static double cosine(float[] anchor, int offset, float[] vector) {
    double dot = 0;
    double anchorNorm = 0;
    double vectorNorm = 0;
    for (int i = 0; i < vector.length; i++) {
      double a = anchor[offset + i];
      double b = vector[i];
      dot += a * b;
      anchorNorm += a * a;
      vectorNorm += b * b;
    }
    if (anchorNorm == 0 || vectorNorm == 0) {
      return 0;
    }
    return dot / (Math.sqrt(anchorNorm) * Math.sqrt(vectorNorm));
  }
}
