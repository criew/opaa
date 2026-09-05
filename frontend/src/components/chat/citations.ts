import type { SourceReference } from '../../types/api'

/**
 * The backend's citation marker (QueryService): `【source: <documentId>#<chunk> | <fileName>】`.
 * `SourceReference.documentId` (#739) is the join key between the answer text and the source list;
 * the file name is only a fallback for persisted legacy messages predating #739 (#590).
 */
export const CITATION_MARKER_RE = /【source:\s*([a-zA-Z0-9-]+#\d+)\s*\|\s*(.+?)\s*】/g

export interface CitationDoc {
  fileName: string
  /** Footnote numbers pointing at this document, in ascending order; empty when the source was
   *  cited by the backend but never referenced in the text. */
  numbers: number[]
  /** The matching source metadata, when the backend listed one for this file name. */
  source: SourceReference | undefined
  /** #739: the document id the backend's citation deep link opens - undefined for a synthetic
   *  entry (#386) with no matching retrieved document, same as {@link SourceReference.documentId}
   *  it is carried straight through from. */
  documentId: string | null | undefined
  /** #1102: this row's position in the backend's `sources` array - the order the retrieval
   *  pipeline settled on, which the Belegfenster sorts and numbers by. `Number.MAX_SAFE_INTEGER`
   *  when no
   *  source matched (a persisted legacy message whose snapshot lists none). */
  sourceIndex: number
  /** #667: the distinct Fundorte of this row's footnotes, in footnote order - "S. 2–4",
   *  "Abschn. 4.2 Fristsetzung" - resolved from the marker's chunk index via
   *  {@link SourceReference.chunkLocations}. Empty when the pipeline knew none. */
  locations: string[]
}

export interface CitationIndex {
  /** Footnote number per marker key (`documentId#chunk`), in order of first appearance. */
  numberByKey: Map<string, number>
  /** Row index in {@link docs} per footnote number - the in-text anchors point at rows. */
  docIndexByNumber: Map<number, number>
  /** Cited documents in first-appearance order, then cited-but-unreferenced sources. */
  docs: CitationDoc[]
  /** Checked but uncited sources - the collapsible tail of the block (mockup 1a). A filter over
   *  `sources`, so this arrives in the backend's order. */
  uncited: SourceReference[]
  /** #1102: position in the backend's `sources` array per source, for the rows that carry the
   *  {@link SourceReference} itself rather than a resolved {@link CitationDoc} - the Belegfenster
   *  labels every row with that position ("Rang n"). */
  sourceIndexByReference: Map<SourceReference, number>
  /** Distinct cited passages ("n Stellen"). */
  markerCount: number
  /** #667: Fundort per footnote number, for the numbers the backend could locate. */
  locationByNumber: Map<number, string>
}

/** Resolves an answer's citation markers into footnote numbers and the Fundstellen rows (#590). */
export function buildCitationIndex(
  content: string,
  sources: SourceReference[] | undefined,
): CitationIndex {
  const numberByKey = new Map<string, number>()
  const docIndexByNumber = new Map<number, number>()
  const locationByNumber = new Map<number, string>()
  const docs: CitationDoc[] = []
  // Rows are identified by documentId when available, falling back to fileName (see below) - the
  // same fallback key is used both to find a marker's source and to decide whether a cited/uncited
  // source already has a row.
  const docIndexByRowKey = new Map<string, number>()
  const citedSources = (sources ?? []).filter((s) => s.cited)
  // The text is the truth (#592): a marker's file counts as cited even when the source list
  // still flags it uncited, so one document never shows up in both groups.
  //
  // PR #745 review: two distinct documents can share a fileName (#739 stopped merging those on the
  // backend), so a fileName-keyed map resolves "last wins" to the wrong SourceReference. The marker
  // itself already carries the documentId (`CITATION_MARKER_RE` group 1, `<documentId>#<chunk>`), so
  // resolve by documentId first and only fall back to fileName for persisted legacy messages
  // (`chat_messages.sources` is a JSON snapshot and may predate #739, carrying no documentId at all).
  const sourceByDocumentId = new Map(
    (sources ?? []).filter((s) => s.documentId != null).map((s) => [s.documentId as string, s]),
  )
  const sourceByFileName = new Map((sources ?? []).map((s) => [s.fileName, s]))
  const indexBySource = new Map((sources ?? []).map((s, i) => [s, i]))

  function sourceIndexOf(source: SourceReference | undefined): number {
    // Both branches sort an unresolvable row last, never first: a row without a source has no
    // pipeline position, and neither has one whose source is not part of this message's list.
    if (source === undefined) {
      return Number.MAX_SAFE_INTEGER
    }
    return indexBySource.get(source) ?? Number.MAX_SAFE_INTEGER
  }

  function resolveSource(
    documentId: string | undefined,
    fileName: string,
  ): SourceReference | undefined {
    if (documentId !== undefined) {
      const byId = sourceByDocumentId.get(documentId)
      if (byId !== undefined) {
        return byId
      }
    }
    return sourceByFileName.get(fileName)
  }

  function rowKey(source: SourceReference | undefined, fileName: string): string {
    return source?.documentId ?? fileName
  }

  // #667: the marker's chunk index (`<documentId>#<chunk>`) picks the location the backend
  // stored for exactly that chunk - the only join there is between a footnote and a Fundort.
  function resolveLocation(source: SourceReference | undefined, key: string): string | undefined {
    const chunkIndex = Number(key.split('#')[1])
    const match = source?.chunkLocations?.find((entry) => entry.chunkIndex === chunkIndex)
    return match?.location ?? undefined
  }

  const regex = new RegExp(CITATION_MARKER_RE.source, 'g')
  let match: RegExpExecArray | null
  while ((match = regex.exec(content)) !== null) {
    const key = match[1]
    const fileName = match[2]
    let number = numberByKey.get(key)
    if (number === undefined) {
      number = numberByKey.size + 1
      numberByKey.set(key, number)
      const markerDocumentId = key.split('#')[0]
      const source = resolveSource(markerDocumentId, fileName)
      const docKey = rowKey(source, fileName)
      let docIndex = docIndexByRowKey.get(docKey)
      if (docIndex === undefined) {
        docIndex = docs.length
        docIndexByRowKey.set(docKey, docIndex)
        docs.push({
          fileName,
          numbers: [],
          source,
          documentId: source?.documentId,
          sourceIndex: sourceIndexOf(source),
          locations: [],
        })
      }
      docs[docIndex].numbers.push(number)
      docIndexByNumber.set(number, docIndex)
      const location = resolveLocation(source, key)
      if (location !== undefined) {
        locationByNumber.set(number, location)
        if (!docs[docIndex].locations.includes(location)) {
          docs[docIndex].locations.push(location)
        }
      }
    }
  }

  for (const source of citedSources) {
    const docKey = rowKey(source, source.fileName)
    if (!docIndexByRowKey.has(docKey)) {
      docIndexByRowKey.set(docKey, docs.length)
      docs.push({
        fileName: source.fileName,
        numbers: [],
        source,
        documentId: source.documentId,
        sourceIndex: sourceIndexOf(source),
        locations: [],
      })
    }
  }

  return {
    numberByKey,
    docIndexByNumber,
    docs,
    uncited: (sources ?? []).filter(
      (s) => !s.cited && !docIndexByRowKey.has(rowKey(s, s.fileName)),
    ),
    sourceIndexByReference: indexBySource,
    markerCount: numberByKey.size,
    locationByNumber,
  }
}

/** The element id of a Fundstellen row, shared between in-text anchors and the block (#590). */
export function citationRowId(messageId: string, docIndex: number): string {
  return `fundstelle-${messageId}-${docIndex}`
}

/**
 * #1066 (ADR-0024; Maintainer-Beschluss vom 04.09.2026 am Epic #1065): the Beleg's metadata line
 * ("Dienstanweisung IT-Nutzung · Dienstanweisung · 12.03.2026"), rendered from the generic
 * field-value list without any field knowledge - the backend supplies label, display text and
 * origin per entry, and a library's own fields (#1071) simply appear as further entries. An
 * empty field is not in the list, so it never renders (metadata-schema.md, Wirkstelle 3); a value
 * a model derived is marked as such, so it never looks like a read one. `undefined` when the list
 * is absent or empty, so callers omit the line entirely. Shared between {@code SourceFootnotes}
 * and {@code SourceEvidenceDrawer}.
 */
export function formatMetadataLine(source: SourceReference | undefined): string | undefined {
  const entries = (source?.metadata ?? []).filter((entry) => !entry.detailOnly)
  if (entries.length === 0) return undefined
  return entries
    .map((entry) => `${entry.displayValue}${entry.origin === 'DERIVED' ? ' (abgeleitet)' : ''}`)
    .join(' · ')
}

/**
 * #1242: the entries the one-line Beleg deliberately leaves out - a mail's recipient list is
 * unbounded and identifies no passage - as labelled "Label: Wert" pairs for the detail view.
 * `undefined` when the source carries none.
 */
export function formatMetadataDetails(source: SourceReference | undefined): string | undefined {
  const entries = (source?.metadata ?? []).filter((entry) => entry.detailOnly)
  if (entries.length === 0) return undefined
  return entries.map((entry) => `${entry.label}: ${entry.displayValue}`).join(' · ')
}

/**
 * #1070: the mark of a hit the Leerwert rule kept - "ohne Angabe" - for a source whose document
 * carried no value for a filtered field (metadata-schema.md, "Leerwerte schließen nicht aus").
 * Undefined without an active filter and for a source that matched on every filtered field, so
 * the mark only ever appears where it makes a statement.
 */
export function metadataFilterMatchLabel(source: SourceReference | undefined): string | undefined {
  return source?.metadataFilterMatch === 'NO_VALUE' ? 'ohne Angabe' : undefined
}

/**
 * #1066: the accessible name of the metadata line - every entry as "Label: Wert", so a screen
 * reader hears what a sighted reader infers from the value alone.
 */
export function describeMetadata(source: SourceReference | undefined): string | undefined {
  const entries = (source?.metadata ?? []).filter((entry) => !entry.detailOnly)
  if (entries.length === 0) return undefined
  return entries.map((entry) => `${entry.label}: ${entry.displayValue}`).join(', ')
}
