import type {
  RetrievalCandidateOutcome,
  RetrievalStage,
  RetrievalStageStatus,
  RetrievalVerdictReason,
  SearchModelRole,
  SearchModelRoleStatusResponse,
  SearchPathStatusResponse,
  TrackedDocumentResponse,
} from '@/shared/api/types'
import { plural } from '@/shared/utils/format'

export function formatMetadataValue(value: unknown): string {
  if (value == null) return '—'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}

export const ROLE_LABELS: Record<SearchModelRole, string> = {
  CHAT: 'Chat',
  EMBEDDING: 'Einbettung',
  RERANK: 'Reranking',
  TRANSCRIPTION: 'Transkription',
}

export const ROLE_STATE_LABELS: Record<SearchModelRoleStatusResponse['state'], string> = {
  ACTIVE: 'Aktiv und erreichbar',
  DISABLED: 'Ausdrücklich abgeschaltet',
  UNCONFIGURED: 'Eingeschaltet, aber unbelegt',
  UNREACHABLE: 'Eingeschaltet, aber nicht erreichbar',
}

export const PATH_LABELS: Record<SearchPathStatusResponse['path'], string> = {
  VECTOR: 'Vektorsuche',
  FULL_TEXT: 'Volltextsuche',
}

export const PATH_STATE_LABELS: Record<SearchPathStatusResponse['state'], string> = {
  ACTIVE: 'Aktiv',
  DISABLED: 'Abgeschaltet',
  INCOMPLETE: 'Unvollständig',
  OUTDATED: 'Nachzug ausstehend',
  SUSPENDED: 'Ausgesetzt',
}

export const STAGE_LABELS: Record<RetrievalStage, string> = {
  SEARCH_SCOPE: 'Suchbereich',
  METADATA_FILTER: 'Metadatenfilter',
  SUB_QUERY_DECOMPOSITION: 'Teilfragen',
  VECTOR_SEARCH: 'Vektorsuche',
  FULL_TEXT_SEARCH: 'Volltextsuche',
  MMR_SELECTION: 'Vielfaltsauswahl',
  RANK_FUSION: 'Fusion',
  RERANK: 'Neubewertung (Reranking)',
  RELEVANCE_CUTOFF: 'Relevanzschnitt',
  DOCUMENT_COMPLETION: 'Dokument-Vervollständigung',
}

export const STAGE_STATUS_LABELS: Record<RetrievalStageStatus, string> = {
  EXECUTED: 'Ausgeführt',
  DISABLED: 'Abgeschaltet',
  NOT_REACHED: 'Nicht erreicht',
  UNAVAILABLE: 'Eingeschaltet, aber nicht verfügbar',
}

export const OUTCOME_LABELS: Record<RetrievalCandidateOutcome, string> = {
  ADDED: 'Hereingekommen',
  KEPT: 'Behalten',
  DROPPED: 'Verdrängt',
}

export const REASON_LABELS: Record<RetrievalVerdictReason, string> = {
  RETRIEVED_BY_SEARCH: 'Von der Suche gefunden',
  WITHIN_BUDGET: 'Innerhalb der Auswahlgrenze',
  GUARANTEED_LIST_TOP: 'Als Spitzentreffer seiner Liste fest übernommen',
  DISPLACED_BY_GUARANTEED_LIST_TOP: 'Von einem fest übernommenen Spitzentreffer verdrängt',
  OUTSIDE_FUSION_BUDGET: 'Nach der Fusion unterhalb der Auswahlgrenze',
  OUTSIDE_RERANK_BUDGET: 'Nach der Neubewertung unterhalb der Auswahlgrenze',
  BELOW_RELEVANCE_CUTOFF: 'Unterhalb des Relevanzschnitts',
  COMPLETED_AS_SIBLING: 'Als weiterer Abschnitt desselben Dokuments ergänzt',
  EVICTED_BY_DOCUMENT_COMPLETION_TIER_1:
    'Von der Dokument-Vervollständigung verdrängt (schwächster Abschnitt seines Dokuments)',
  EVICTED_BY_DOCUMENT_COMPLETION_TIER_2:
    'Von der Dokument-Vervollständigung verdrängt (schwächster Abschnitt der Auswahl)',
}

/**
 * The plain-language answer to "why does this document not appear?" - the one distinction the
 * whole diagnosis exists for: an indexing problem, a ranking problem, or a rights problem.
 */
export function trackedDocumentMessage(tracked: TrackedDocumentResponse): {
  severity: 'success' | 'info' | 'warning'
  text: string
} {
  const name = tracked.fileName ?? 'Das Dokument'
  switch (tracked.outcome) {
    case 'IN_FINAL_SELECTION':
      return {
        severity: 'success',
        text: `„${name}“ steht mit ${plural(tracked.selectedChunkCount, 'Abschnitt', 'Abschnitten')} in der Endauswahl.`,
      }
    case 'IN_LOCKED_AREA':
      return {
        severity: 'info',
        text:
          'Das verfolgte Dokument liegt in einem gesperrten Suchbereich. Die Diagnose trifft dazu ' +
          'keine Aussage - weder zu seinem Titel noch dazu, ob die Person es lesen darf.',
      }
    case 'OUTSIDE_SEARCH_SCOPE':
      // Without a name the run is a person context: it may then not say "Rechtefrage" either,
      // because a locked area is excluded for its own reason (Leitplanke (e)).
      return {
        severity: 'info',
        text: tracked.fileName
          ? `„${name}“ liegt außerhalb des Suchbereichs dieses Rechtekontexts` +
            `${tracked.libraryName ? ` (Bibliothek „${tracked.libraryName}“)` : ''}. ` +
            'Keine Suchstufe konnte es erreichen - das ist eine Rechtefrage, keine Suchfrage.'
          : 'Das verfolgte Dokument liegt außerhalb des durchsuchten Rechtekontexts. Keine ' +
            'Suchstufe konnte es erreichen; aus dieser Bibliothek zeigt die Diagnose nichts.',
      }
    case 'NOT_RETRIEVED':
      return {
        severity: 'warning',
        text:
          `„${name}“ wurde von keiner Suchstufe gefunden. Es ist im Suchbereich, aber keiner ` +
          'seiner Abschnitte war Kandidat - das deutet auf ein Indexierungs- oder ' +
          'Zuschnittsproblem hin, nicht auf die Rangfolge.',
      }
    case 'DISPLACED':
    default:
      return {
        severity: 'warning',
        text:
          `„${name}“ wurde gefunden (${plural(tracked.retrievedChunkCount, 'Abschnitt', 'Abschnitte')}), aber in der ` +
          `Stufe „${tracked.displacedAtStage ? STAGE_LABELS[tracked.displacedAtStage] : 'unbekannt'}“ ` +
          `verdrängt${tracked.displacedReason ? `: ${REASON_LABELS[tracked.displacedReason]}` : ''}. ` +
          'Das ist ein Problem der Rangfolge, nicht der Indexierung.',
      }
  }
}
