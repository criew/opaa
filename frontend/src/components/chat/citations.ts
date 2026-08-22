import type { SourceReference } from '../../types/api'

/**
 * The backend's citation marker (QueryService): `【source: <documentId>#<chunk> | <fileName>】`.
 * `SourceReference` carries no document id, so the file name inside the marker is the join key
 * between the answer text and the source list (#590).
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
}

export interface CitationIndex {
  /** Footnote number per marker key (`documentId#chunk`), in order of first appearance. */
  numberByKey: Map<string, number>
  /** Row index in {@link docs} per footnote number - the in-text anchors point at rows. */
  docIndexByNumber: Map<number, number>
  /** Cited documents in first-appearance order, then cited-but-unreferenced sources. */
  docs: CitationDoc[]
  /** Checked but uncited sources - the collapsible tail of the block (mockup 1a). */
  uncited: SourceReference[]
  /** Distinct cited passages ("n Stellen"). */
  markerCount: number
}

/** Resolves an answer's citation markers into footnote numbers and the Fundstellen rows (#590). */
export function buildCitationIndex(
  content: string,
  sources: SourceReference[] | undefined,
): CitationIndex {
  const numberByKey = new Map<string, number>()
  const docIndexByNumber = new Map<number, number>()
  const docs: CitationDoc[] = []
  const docIndexByFileName = new Map<string, number>()
  const citedSources = (sources ?? []).filter((s) => s.cited)
  // The text is the truth (#592): a marker's file counts as cited even when the source list
  // still flags it uncited, so one document never shows up in both groups.
  const sourceByFileName = new Map((sources ?? []).map((s) => [s.fileName, s]))

  const regex = new RegExp(CITATION_MARKER_RE.source, 'g')
  let match: RegExpExecArray | null
  while ((match = regex.exec(content)) !== null) {
    const key = match[1]
    const fileName = match[2]
    let number = numberByKey.get(key)
    if (number === undefined) {
      number = numberByKey.size + 1
      numberByKey.set(key, number)
      let docIndex = docIndexByFileName.get(fileName)
      if (docIndex === undefined) {
        docIndex = docs.length
        docIndexByFileName.set(fileName, docIndex)
        docs.push({
          fileName,
          numbers: [],
          source: sourceByFileName.get(fileName),
          documentId: sourceByFileName.get(fileName)?.documentId,
        })
      }
      docs[docIndex].numbers.push(number)
      docIndexByNumber.set(number, docIndex)
    }
  }

  for (const source of citedSources) {
    if (!docIndexByFileName.has(source.fileName)) {
      docIndexByFileName.set(source.fileName, docs.length)
      docs.push({
        fileName: source.fileName,
        numbers: [],
        source,
        documentId: source.documentId,
      })
    }
  }

  return {
    numberByKey,
    docIndexByNumber,
    docs,
    uncited: (sources ?? []).filter((s) => !s.cited && !docIndexByFileName.has(s.fileName)),
    markerCount: numberByKey.size,
  }
}

/** The element id of a Fundstellen row, shared between in-text anchors and the block (#590). */
export function citationRowId(messageId: string, docIndex: number): string {
  return `fundstelle-${messageId}-${docIndex}`
}
