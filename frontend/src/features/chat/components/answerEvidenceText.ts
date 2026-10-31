import type { AnswerEvidence } from '@/shared/api/types'

/** The Belegstand of an answer as the line under it shows it: level word and one reason. */
export interface AnswerEvidenceText {
  label: string
  reason: string
}

/** At most this many uncovered sub-queries are named; more are only counted. */
const NAMED_SUB_QUERIES = 2

function plural(count: number, one: string, many: string): string {
  return count === 1 ? `1 ${one}` : `${count} ${many}`
}

/**
 * Level word and reason of a Belegstand (docs/handbuch/spaces/suche.md, Abschnitt 7). The reason
 * names what keeps an answer from the level above, never a score.
 */
export function describeAnswerEvidence(evidence: AnswerEvidence): AnswerEvidenceText {
  switch (evidence.level) {
    case 'SUPPORTED':
      return { label: 'Belegt', reason: 'alle Belege bestätigt' }
    case 'PARTIALLY_SUPPORTED': {
      const reasons: string[] = []
      if (evidence.unconfirmedCitations > 0) {
        reasons.push(`${plural(evidence.unconfirmedCitations, 'Beleg', 'Belege')} nicht bestätigt`)
      }
      const uncovered = evidence.uncoveredSubQueries
      if (uncovered.length > NAMED_SUB_QUERIES) {
        reasons.push(`zu ${uncovered.length} Teilfragen keine Fundstelle`)
      } else {
        uncovered.forEach((query) => reasons.push(`zur Teilfrage „${query}“ keine Fundstelle`))
      }
      if (evidence.truncated) reasons.push('Antwort abgebrochen')
      return { label: 'Teilweise belegt', reason: reasons.join(' · ') }
    }
    default: {
      let reason: string
      if (!evidence.knowledgeSearched) reason = 'kein Wissen durchsucht'
      else if (!evidence.passagesFound) reason = 'keine Fundstelle im durchsuchten Bereich'
      else if (evidence.unconfirmedCitations > 0) reason = 'kein Beleg bestätigt'
      else reason = 'die Antwort nennt keine Fundstelle'
      return { label: 'Nicht belegt', reason: `${reason} · vor Verwendung prüfen` }
    }
  }
}
