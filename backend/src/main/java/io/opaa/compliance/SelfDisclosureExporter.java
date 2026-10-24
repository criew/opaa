package io.opaa.compliance;

import java.io.IOException;

/**
 * Port through which a module hands the self-disclosure of a person the rows of one category
 * (docs/features/security-and-compliance.md, "Selbstauskunft"). An implementation is a
 * {@code @Component} in the module owning the data, declares its category as {@code public static
 * final SelfDisclosureCategory CATEGORY} and returns it from {@link #category()}; there is no
 * central list.
 *
 * <p>Contract of {@link #export}:
 *
 * <ul>
 *   <li>It is called outside any transaction. The exporter reads in short read-only transactions of
 *       its own, page by page, and writes each page after its transaction ended; it never collects
 *       all rows in memory.
 *   <li>It reads only the rows of {@link SelfDisclosureSubject#accountId()} and writes only the
 *       fields of its {@link SelfDisclosureCategory positive list}; the sink refuses anything else.
 *   <li>Another person appears as {@link SelfDisclosureLabels#OTHER_PERSON}, a system process or
 *       service account as {@link SelfDisclosureLabels#SYSTEM}, never by name, address, id or
 *       pseudonym. Justifications of administrative or audit acts never go out.
 *   <li>An {@link IOException} or other exception of the sink ends the export; the exporter does
 *       not catch it. A long read without output is allowed: the reservation is kept alive by time,
 *       and an export told to end (deadline, shutdown) ends at its next record.
 * </ul>
 */
public interface SelfDisclosureExporter {

  /** The category this exporter delivers; the same instance as its {@code CATEGORY} constant. */
  SelfDisclosureCategory category();

  /**
   * Writes every record of {@code subject} in this category, section by section, to {@code sink}.
   */
  void export(SelfDisclosureSubject subject, SelfDisclosureSink sink) throws IOException;
}
