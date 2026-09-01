package io.opaa.indexing.pipeline.office;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DoS-hardening limits {@link OdtDocumentPipeline}/{@link OdpDocumentPipeline} apply against a
 * pathological ODT/ODP file - the same style of operator-tunable ceiling {@link
 * io.opaa.indexing.pipeline.tabular.TabularProperties} already carries for {@code
 * TabularDocumentPipeline}'s ODS reader, deliberately its own property block for the same reason (a
 * concern specific to these two pipelines, not a fit for {@link
 * io.opaa.indexing.IndexingProperties}'s many positional-record call sites).
 *
 * <p>Unlike the ODS reader, neither {@code table:number-columns-repeated} nor {@code
 * table:number-rows-repeated} is expanded here - a table inside a text document or a slide is read
 * element-for-element. {@code text:s}'s own {@code text:c} repeat count is a distinct amplification
 * vector, though: it is guarded by {@link #maxSpaceRepeat} below.
 *
 * @param maxContentXmlBytes the maximum number of bytes read from an ODT/ODP file's {@code
 *     content.xml} entry before parsing aborts - the zip-bomb guard, mirroring {@code
 *     TabularProperties#maxOdsContentXmlBytes}. Default 10 MiB.
 * @param maxOdtParagraphs the maximum number of paragraph/heading/table elements read from an ODT
 *     document before parsing aborts - a second guard alongside {@link #maxContentXmlBytes} against
 *     a pathologically large number of small elements. Default 50 000.
 * @param maxOdpSlides the maximum number of {@code draw:page} slides read from an ODP presentation
 *     before parsing aborts - the ODP counterpart of {@link #maxOdtParagraphs}. Default 5 000.
 * @param maxSpaceRepeat the maximum number of spaces a single {@code text:s} element expands to -
 *     without this, {@code text:c} lets a few bytes of markup request an arbitrarily large
 *     in-memory string (mirrors {@code TabularProperties#maxOdsCellRepeat}'s reasoning for the ODS
 *     reader's own repeat attribute). Default 1 000.
 * @param maxTextCharacters the maximum number of characters accumulated, across the whole document,
 *     into a paragraph/cell text buffer before parsing aborts - {@link #maxSpaceRepeat} bounds one
 *     {@code text:s} element, but a paragraph resets its buffer only once per {@code text:h}/{@code
 *     text:p} and can carry an unbounded number of {@code text:s} elements, so the per-element cap
 *     alone does not bound total memory use (mirrors {@code TabularProperties#maxOdsRows}'s
 *     cumulative, rather than per-row, reasoning). Default 10 000 000.
 */
@ConfigurationProperties(prefix = "opaa.indexing.odf")
public record OdfProperties(
    long maxContentXmlBytes,
    int maxOdtParagraphs,
    int maxOdpSlides,
    int maxSpaceRepeat,
    long maxTextCharacters) {

  public OdfProperties {
    if (maxContentXmlBytes <= 0) {
      maxContentXmlBytes = 10_485_760L; // 10 MiB
    }
    if (maxOdtParagraphs <= 0) {
      maxOdtParagraphs = 50_000;
    }
    if (maxOdpSlides <= 0) {
      maxOdpSlides = 5_000;
    }
    if (maxSpaceRepeat <= 0) {
      maxSpaceRepeat = 1_000;
    }
    if (maxTextCharacters <= 0) {
      maxTextCharacters = 10_000_000L;
    }
  }
}
