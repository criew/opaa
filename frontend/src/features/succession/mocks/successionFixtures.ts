import type { SuccessionEntryResponse, SuccessionKind } from '@/shared/api/types'

/**
 * The life cycle's operations list — a file of its own, so that `fixtures.ts` does not
 * keep growing. The rows deliberately have no person evaluation axis: the previous owner
 * is text in the row and nowhere a key.
 */
export const mockSuccessionEntries: Record<SuccessionKind, SuccessionEntryResponse[]> = {
  OPEN_SUCCESSION: [
    {
      caseId: 'case-1',
      objectType: 'ASSET',
      assetType: 'KNOWLEDGE_LIBRARY',
      objectId: 'lib-1',
      objectName: 'Bauakten Referat 50',
      addressee: 'SYSTEM_ADMINISTRATION',
      addresseeLabel: 'die Systemverwaltung',
      ownerHint: 'Andrea Vogt (Konto gesperrt)',
      ownerType: 'USER',
      membershipHints: ['war Mitglied von Referat 50'],
      affectedObjects: 0,
      firstSeenAt: '2025-06-01T08:00:00Z',
      highlighted: true,
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
    },
  ],
}
