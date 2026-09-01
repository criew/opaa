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
 * element-for-element, so there is no amplification vector those attributes would otherwise be a
 * guard against.
 *
 * @param maxContentXmlBytes the maximum number of bytes read from an ODT/ODP file's {@code
 *     content.xml} entry before parsing aborts - the zip-bomb guard, mirroring {@code
 *     TabularProperties#maxOdsContentXmlBytes}. Default 10 MiB.
 * @param maxOdtParagraphs the maximum number of paragraph/heading/table elements read from an ODT
 *     document before parsing aborts - a second guard alongside {@link #maxContentXmlBytes} against
 *     a pathologically large number of small elements. Default 50 000.
 * @param maxOdpSlides the maximum number of {@code draw:page} slides read from an ODP presentation
 *     before parsing aborts - the ODP counterpart of {@link #maxOdtParagraphs}. Default 5 000.
 */
@ConfigurationProperties(prefix = "opaa.indexing.odf")
public record OdfProperties(long maxContentXmlBytes, int maxOdtParagraphs, int maxOdpSlides) {

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
  }
}
