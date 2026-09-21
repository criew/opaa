import type {
  CapabilityOverviewResponse,
  DirectorySyncPendingPlanResponse,
  DirectorySyncReportResponse,
  DirectorySyncStatusResponse,
  GroupEffectsResponse,
} from '../types/api'

/**
 * Die Mocks der Verwaltung von Gruppen, Anlegerechten und Verzeichnisabgleich (#1821) — eigene
 * Datei, damit `fixtures.ts` nicht weiter wächst und parallele Arbeit sich nicht ins Gehege kommt.
 */
export const mockCapabilityOverview: CapabilityOverviewResponse[] = [
  {
    capability: 'CREATE_SPACE',
    label: 'Spaces anlegen',
    statement: 'Alle Konten dürfen Spaces anlegen.',
    grants: [
      {
        id: 'capability-grant-space-all',
        capability: 'CREATE_SPACE',
        subjectType: 'ALL_ACCOUNTS',
        subjectId: null,
        subjectName: null,
        grantedByUserId: null,
        createdAt: '2026-09-01T08:00:00Z',
      },
    ],
  },
  {
    capability: 'CREATE_LIBRARY',
    label: 'Bibliotheken für Uploads anlegen',
    statement: 'Alle Konten dürfen Bibliotheken für Uploads anlegen.',
    grants: [
      {
        id: 'capability-grant-library-all',
        capability: 'CREATE_LIBRARY',
        subjectType: 'ALL_ACCOUNTS',
        subjectId: null,
        subjectName: null,
        grantedByUserId: null,
        createdAt: '2026-09-01T08:00:00Z',
      },
    ],
  },
  {
    capability: 'CREATE_CONNECTOR_LIBRARY',
    label: 'Konnektorbibliotheken anlegen',
    statement: 'Alle Konten dürfen Konnektorbibliotheken anlegen.',
    grants: [
      {
        id: 'capability-grant-connector-all',
        capability: 'CREATE_CONNECTOR_LIBRARY',
        subjectType: 'ALL_ACCOUNTS',
        subjectId: null,
        subjectName: null,
        grantedByUserId: null,
        createdAt: '2026-09-01T08:00:00Z',
      },
    ],
  },
  {
    capability: 'CREATE_INTERNAL_GROUP',
    label: 'Interne Gruppen anlegen',
    statement: 'Niemand darf interne Gruppen anlegen; die Systemverwaltung kann es ohnehin.',
    grants: [],
  },
]

export const mockGroupEffects: GroupEffectsResponse[] = [
  {
    groupId: 'group-referat-50',
    name: 'Referat 50',
    origin: 'PROVIDER',
    providerId: 'oidc-provider-beschaeftigte',
    sourcePath: '/Haus A/Abteilung 5/Referat 50',
    dissolved: false,
    protectedGroup: false,
    assetGrants: 12,
    grantedAssets: 7,
    spaceMemberships: 2,
    spaces: 2,
    ownedAssets: 1,
    capabilities: 0,
    scopedAuthorizations: 0,
    summary:
      '12 Berechtigungen an 7 Objekten, 2 Space-Mitgliedschaften in 2 Spaces, Eigentümerin von 1 Objekt',
  },
  {
    groupId: 'group-phoenix',
    name: 'Projektbeteiligte Phoenix',
    origin: 'INTERNAL',
    providerId: null,
    sourcePath: null,
    dissolved: false,
    protectedGroup: false,
    assetGrants: 1,
    grantedAssets: 1,
    spaceMemberships: 0,
    spaces: 0,
    ownedAssets: 0,
    capabilities: 0,
    scopedAuthorizations: 0,
    summary: '1 Berechtigung an 1 Objekt',
  },
  {
    groupId: 'group-personalrat',
    name: 'Personalrat',
    origin: 'INTERNAL',
    providerId: null,
    sourcePath: null,
    dissolved: false,
    protectedGroup: true,
    assetGrants: 0,
    grantedAssets: 0,
    spaceMemberships: 0,
    spaces: 0,
    ownedAssets: 0,
    capabilities: 0,
    scopedAuthorizations: 0,
    summary: '',
  },
]

export const mockDirectorySyncReport: DirectorySyncReportResponse = {
  outcome: 'DRY_RUN',
  generatedAt: '2026-09-21T06:00:00Z',
  groupsCreated: [
    {
      externalId: 'directory-guid-referat-52',
      name: 'Referat 52',
      previousName: null,
      sourcePath: '/Haus A/Abteilung 5/Referat 52',
      memberCount: 9,
    },
  ],
  groupsRenamed: [],
  groupsDissolved: [
    {
      externalId: 'directory-guid-referat-50',
      name: 'Referat 50',
      previousName: null,
      sourcePath: '/Haus A/Abteilung 5/Referat 50',
      memberCount: 23,
    },
  ],
  unmaintainedTokenGroups: [],
  membershipChanges: [
    {
      externalId: 'directory-guid-referat-52',
      name: 'Referat 52',
      added: [{ userId: 'owner-1', displayName: 'Alice' }],
      removed: [],
    },
  ],
  accountsLocked: [{ userId: 'owner-2', displayName: 'Bernd Bauer' }],
  accountsUnlocked: [],
  accountLocksWithheld: [],
  membershipsAdded: 1,
  membershipsRemoved: 23,
  unresolvedMemberCount: 0,
  changedFraction: 0.67,
  thresholdFraction: 0.3,
  message: 'Trockenlauf: Es wurde nichts geschrieben.',
}

export const mockPendingPlan: DirectorySyncPendingPlanResponse = {
  id: 'plan-1',
  providerId: 'oidc-provider-beschaeftigte',
  createdAt: '2026-09-18T06:00:00Z',
  report: { ...mockDirectorySyncReport, outcome: 'PENDING_CONFIRMATION' },
}

export const mockDirectorySyncStatus: DirectorySyncStatusResponse[] = [
  {
    providerId: 'oidc-provider-beschaeftigte',
    providerDisplayName: 'Verzeichnisdienst',
    providerEnabled: true,
    enabled: true,
    intervalMinutes: 360,
    lastRunAt: '2026-09-18T06:00:00Z',
    lastOutcome: 'PENDING_CONFIRMATION',
    lastMessage: 'Über der Schwelle: Der Plan wartet auf eine Entscheidung.',
    lastAppliedAt: '2026-09-17T06:00:00Z',
    lastChangedFraction: 0.67,
    pendingPlan: {
      id: 'plan-1',
      createdAt: '2026-09-18T06:00:00Z',
      changedFraction: 0.67,
      membershipsRemoved: 23,
      accountsLocked: 1,
    },
  },
]
