/**
 * The read-only operational view of retrieval: which model roles and search paths are active, how
 * far each library's indexes are built, and what the pipeline actually did with one test question
 * (docs/features/hybrid-retrieval.md, "Die Administrationsseite ‚Suche &amp; Indexierung'").
 *
 * <p>This package changes no domain data. It writes protocol entries only: a diagnosis run with a
 * rights profile goes into the audit trail, a "Sicht als" run into the diagnostic protocol through
 * {@code io.opaa.diagnosticaccess}. It is deliberately not part of {@code io.opaa.query}: the
 * pipeline answers questions, this package only observes it - and it reads the pipeline's own
 * explanation protocol rather than reproducing any of its decisions.
 */
package io.opaa.searchadmin;
