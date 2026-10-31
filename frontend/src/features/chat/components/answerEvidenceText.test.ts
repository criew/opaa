import { describe, expect, it } from 'vitest'
import type { AnswerEvidence } from '@/shared/api/types'
import { describeAnswerEvidence } from './answerEvidenceText'

const base: AnswerEvidence = {
  level: 'SUPPORTED',
  confirmedPassages: 3,
  confirmedDocuments: 2,
  unconfirmedCitations: 0,
  uncoveredSubQueries: [],
  truncated: false,
  knowledgeSearched: true,
  passagesFound: true,
}

describe('describeAnswerEvidence', () => {
  it('names a supported answer without a score', () => {
    expect(describeAnswerEvidence(base)).toEqual({
      label: 'Belegt',
      reason: 'alle Belege bestätigt',
    })
  })

  it('lists every reason that keeps an answer partially supported', () => {
    expect(
      describeAnswerEvidence({
        ...base,
        level: 'PARTIALLY_SUPPORTED',
        unconfirmedCitations: 1,
        uncoveredSubQueries: ['Gültigkeitsdauer Anwohnerparkausweis'],
        truncated: true,
      }),
    ).toEqual({
      label: 'Teilweise belegt',
      reason:
        '1 Beleg nicht bestätigt · zur Teilfrage „Gültigkeitsdauer Anwohnerparkausweis“ keine Fundstelle · Antwort abgebrochen',
    })
  })

  it('counts instead of naming more than two uncovered sub-queries', () => {
    const { reason } = describeAnswerEvidence({
      ...base,
      level: 'PARTIALLY_SUPPORTED',
      uncoveredSubQueries: ['a', 'b', 'c'],
    })
    expect(reason).toBe('zu 3 Teilfragen keine Fundstelle')
  })

  it.each([
    [{ knowledgeSearched: false, passagesFound: false }, 'kein Wissen durchsucht'],
    [{ passagesFound: false }, 'keine Fundstelle im durchsuchten Bereich'],
    [{ unconfirmedCitations: 2 }, 'kein Beleg bestätigt'],
    [{}, 'die Antwort nennt keine Fundstelle'],
  ])('says why an answer is unsupported (%o)', (overrides, why) => {
    expect(
      describeAnswerEvidence({
        ...base,
        level: 'UNSUPPORTED',
        confirmedPassages: 0,
        confirmedDocuments: 0,
        ...overrides,
      }),
    ).toEqual({ label: 'Nicht belegt', reason: `${why} · vor Verwendung prüfen` })
  })
})
