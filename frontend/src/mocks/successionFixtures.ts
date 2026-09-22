import type { SuccessionEntryResponse, SuccessionKind } from '../types/api'

/**
 * Die Betriebsliste des Lebenszyklus (#1821 gegen #1819) — eigene Datei, damit `fixtures.ts` nicht
 * weiter wächst. Die Zeilen sind absichtlich ohne Auswertungsachse Person: Der frühere Eigentümer
 * steht als Text in der Zeile und nirgends als Schlüssel.
 */
export const mockSuccessionEntries: Record<SuccessionKind, SuccessionEntryResponse[]> = {
  OPEN_SUCCESSION: [
    {
      caseId: 'case-1',
      objectType: 'KNOWLEDGE_LIBRARY',
      objectId: 'lib-1',
      objectName: 'Bauakten Referat 50',
      addressee: 'SYSTEM_ADMINISTRATION',
      addresseeLabel: 'die Systemverwaltung',
      ownerHint: 'Andrea Vogt (Konto gesperrt)',
      membershipHints: ['war Mitglied von Referat 50'],
      affectedObjects: 0,
      firstSeenAt: '2025-06-01T08:00:00Z',
      highlighted: true,
      lastReviewedAt: null,
      lastReviewReason: null,
    },
    {
      caseId: null,
      objectType: 'SPACE',
      objectId: 'space-1',
      objectName: 'Projekt Phoenix',
      addressee: 'SPACE_ADMINS',
      addresseeLabel: 'die übrigen handlungsfähigen ADMIN-Mitglieder des Space',
      ownerHint: null,
      membershipHints: [],
      affectedObjects: 0,
      firstSeenAt: null,
      highlighted: false,
      lastReviewedAt: null,
      lastReviewReason: null,
    },
  ],
  GRANTS_WITHOUT_RECIPIENT: [
    {
      caseId: 'case-2',
      objectType: 'GROUP',
      objectId: 'group-referat-49',
      objectName: 'Referat 49',
      addressee: 'SYSTEM_ADMINISTRATION',
      addresseeLabel: 'die Systemverwaltung',
      ownerHint: null,
      membershipHints: [],
      affectedObjects: 7,
      firstSeenAt: '2026-08-01T08:00:00Z',
      highlighted: false,
      lastReviewedAt: '2026-09-01T08:00:00Z',
      lastReviewReason: 'Reorganisation läuft, Ziel steht noch nicht fest',
    },
  ],
  GROUP_WITHOUT_EFFECT: [
    {
      caseId: 'case-3',
      objectType: 'GROUP',
      objectId: 'group-arbeitskreis',
      objectName: 'Arbeitskreis Digitalisierung',
      addressee: 'SYSTEM_ADMINISTRATION',
      addresseeLabel: 'die Systemverwaltung',
      ownerHint: null,
      membershipHints: [],
      affectedObjects: 0,
      firstSeenAt: '2026-07-01T08:00:00Z',
      highlighted: false,
      lastReviewedAt: null,
      lastReviewReason: null,
    },
  ],
}
