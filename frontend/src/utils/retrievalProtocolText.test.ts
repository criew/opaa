import { describe, expect, it } from 'vitest'
import { translateListLabel, translateStageNote } from './retrievalProtocolText'

/**
 * Every note the retrieval stages can produce today, in the exact wording the backend emits. A note
 * missing here would reach the German administration page in English.
 *
 * The backend side of this coupling is `io.opaa.query.RetrievalNoteTest`: it pins the exact set of
 * note/list-label templates in `io.opaa.query.RetrievalNote`/`RetrievalListLabel` and fails, with a
 * message pointing back at this file, the moment a stage gains a new or reworded template (#1160).
 */
const BACKEND_NOTES = [
  'empty search scope: nothing readable in scope, retrieval halted',
  'search scope: 2 libraries',
  'search scope: 1 library',
  'permission filter applied inside every search of this run, never afterwards',
  'decomposition produced 3 sub-queries',
  'decomposition produced 1 sub-query',
  'decomposition returned nothing (failed or unparsable): single-query fallback',
  'decomposition switched off by configuration: single-query fallback',
  'search query: Gebührenbefreiung wegen Bedürftigkeit',
  'vector search, 3 list(s)',
  'fetch-k 25 per list',
  'similarity threshold 0.5, applied in-query',
  'lexical search path switched off (opaa.query.full-text-search-enabled)',
  'no library of the search scope has a completed full-text backfill',
  "the lexical path stays out of the fusion until a library's backfill is complete",
  'lexical search failed for full-text search · sub-query 2: DataAccessResourceFailureException',
  'full-text search, 3 list(s)',
  'permission filter applied inside the query: 2 of 3 scoped libraries searched, the rest awaiting their backfill',
  'per-list budget 8',
  'mmr-lambda 1.0 (diversity term inactive: plain top-k by relevance)',
  'mmr-lambda 0.7 (diversity term active, cosine similarity of real chunk embeddings)',
  'reciprocal rank fusion over 6 list(s)',
  'budget widened to the rerank candidate window 50',
  'overall budget top-k 8',
  'deduplicated by chunk id: 150 list entries became 62 distinct candidates',
  'reranking switched off through opaa.query.rerank-candidate-count=0',
  "reranking switched off through the rerank model role's own switch (opaa.rerank.enabled / OPAA_RERANK_ENABLED)",
  "the rerank model role is switched on but was not usable when this run started - no endpoint or model is configured for it, or its endpoint did not answer; the role's own state says which (RerankRoleStatusProvider#currentStatus)",
  'no candidate reached this stage; there was nothing to rerank',
  'the rerank model role scored nothing; the fused order was kept and capped at top-k 8',
  'rerank candidate window 50',
  '12 of 50 candidate(s) scored by the rerank model',
  'max-chunks-per-document 3',
  '4 sibling chunk(s) completed from the candidate pool',
  'stage switched off for this run',
  'stage switched on but unavailable: the run continued without it',
  'run halted before this stage: nothing left to retrieve',
]

/**
 * Words that occur only in the English originals. Deliberately not exhaustive over English as such:
 * `Backfill`, `Fusion` and `Reranking` are established German terms of this project's documentation.
 */
const ENGLISH_WORDS =
  /\b(search|scope|budget|switched|stage|query|chunk|library|libraries|permission|threshold|lexical|document|nothing|candidate|candidates|list|entries|distinct|window|sibling|pool)\b/i

describe('translateStageNote', () => {
  it.each(BACKEND_NOTES)('translates %s', (note) => {
    const german = translateStageNote(note)
    expect(german).not.toBe(note)
  })

  it('leaves no English prose in the translated notes', () => {
    for (const note of BACKEND_NOTES) {
      // The user's own search query is echoed verbatim and is not ours to judge.
      if (note.startsWith('search query: ')) {
        continue
      }
      // Configuration keys and parameter names are technical constants and stay as they are.
      const prose = translateStageNote(note)
        .replace(/\bopaa[\w.-]*/g, '')
        .replace(/\bOPAA_[\w]*/g, '')
        .replace(/\b(fetch-k|top-k|mmr-lambda|max-chunks-per-document)\b/g, '')
      expect(prose).not.toMatch(ENGLISH_WORDS)
    }
  })

  it('keeps the numbers of a note that carries them', () => {
    expect(
      translateStageNote(
        'deduplicated by chunk id: 150 list entries became 62 distinct candidates',
      ),
    ).toContain('150')
    expect(
      translateStageNote(
        'deduplicated by chunk id: 150 list entries became 62 distinct candidates',
      ),
    ).toContain('62')
    expect(translateStageNote('reciprocal rank fusion over 6 list(s)')).toContain('6')
    expect(translateStageNote('rerank candidate window 50')).toContain('50')
  })

  it('uses the singular where the count is one', () => {
    expect(translateStageNote('search scope: 1 library')).toContain('1 Bibliothek.')
    expect(translateStageNote('decomposition produced 1 sub-query')).toContain('1 Teilfrage ')
  })

  it('keeps configuration keys and parameter names untranslated', () => {
    expect(translateStageNote('fetch-k 25 per list')).toContain('fetch-k 25')
    expect(translateStageNote('overall budget top-k 8')).toContain('top-k')
    expect(
      translateStageNote('lexical search path switched off (opaa.query.full-text-search-enabled)'),
    ).toContain('opaa.query.full-text-search-enabled')
  })

  it('never shows a Java type to the operator', () => {
    const unusable = translateStageNote(
      "the rerank model role is switched on but was not usable when this run started - no endpoint or model is configured for it, or its endpoint did not answer; the role's own state says which (RerankRoleStatusProvider#currentStatus)",
    )
    expect(unusable).not.toContain('RerankRoleStatusProvider')
    expect(unusable).toContain('Modellrolle')

    const failed = translateStageNote(
      'lexical search failed for full-text search · sub-query 2: DataAccessResourceFailureException',
    )
    expect(failed).not.toContain('Exception')
    expect(failed).toContain('Volltextsuche · Teilfrage 2')
  })

  it('passes an unknown note through rather than swallowing it', () => {
    expect(translateStageNote('a note some later stage invented')).toBe(
      'a note some later stage invented',
    )
  })
})

describe('translateListLabel', () => {
  it('translates the labels the search stages produce', () => {
    expect(translateListLabel('vector search · sub-query 1')).toBe('Vektorsuche · Teilfrage 1')
    expect(translateListLabel('full-text search · sub-query 3')).toBe('Volltextsuche · Teilfrage 3')
    expect(translateListLabel('fused (RRF)')).toBe('fusioniert (RRF)')
  })

  it('names a label-less verdict exactly like the explicit fused label', () => {
    expect(translateListLabel(null)).toBe('fusioniert (RRF)')
    expect(translateListLabel(undefined)).toBe('fusioniert (RRF)')
  })
})
