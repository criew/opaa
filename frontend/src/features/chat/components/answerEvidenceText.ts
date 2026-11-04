import type { AnswerEvidence } from '@/shared/api/types'

/** The Belegstand of an answer as the foot of the answer shows it: level word and the reason. */
export interface AnswerEvidenceText {
  label: string
  /** What keeps the answer from the level above; empty where the count line says it already. */
  reason: string
}

function plural(count: number, one: string, many: string): string {
  return count === 1 ? `1 ${one}` : `${count} ${many}`
}

/**
 * Level word and reason of a Belegstand (docs/handbuch/referenz/wie-die-suche-arbeitet.md). The reason
 * names what keeps an answer from the level above, never a score; uncovered sub-queries are only
 * counted - their wording stays in the Belegfenster.
 */
export function describeAnswerEvidence(evidence: AnswerEvidence): AnswerEvidenceText {
  switch (evidence.level) {
    case 'SUPPORTED':
      return { label: 'Belegt', reason: '' }
    case 'PARTIALLY_SUPPORTED': {
      const reasons: string[] = []
      if (evidence.unconfirmedCitations > 0) {
        reasons.push(`${plural(evidence.unconfirmedCitations, 'Beleg', 'Belege')} nicht bestätigt`)
      }
      const uncovered = evidence.uncoveredSubQueries.length
      if (uncovered > 0) {
        reasons.push(`${plural(uncovered, 'Teilfrage', 'Teilfragen')} ohne Fundstelle`)
      }
      if (evidence.truncated) reasons.push('Antwort abgebrochen')
      return { label: 'Teilweise belegt', reason: reasons.join(' · ') }
    }
    default: {
      let reason = ''
      if (!evidence.knowledgeSearched) reason = 'kein Wissen durchsucht'
      else if (!evidence.passagesFound) reason = 'keine Fundstelle im durchsuchten Bereich'
      else if (evidence.unconfirmedCitations > 0) reason = 'kein Beleg bestätigt'
      return { label: 'Nicht belegt', reason }
    }
  }
}
