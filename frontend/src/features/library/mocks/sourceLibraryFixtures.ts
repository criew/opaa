import type { LibraryResponse } from '@/shared/api/types'

/**
 * One connected library per remote source type, so the detail page and its tab „Quelle“ can be
 * opened directly in mock mode (`/libraries/<id>`). The fixture scenarios widen their selections
 * (`libraryScenarioFixtures.ts`).
 */

const CREATED = '2026-09-10T08:00:00Z'
const DAILY = {
  frequency: 'DAILY' as const,
  hour: 6,
  minute: 0,
  nextRunAt: '2026-10-25T06:00:00Z',
}

function base(
  id: string,
  name: string,
  description: string,
  sourceType: LibraryResponse['sourceType'],
): LibraryResponse {
  return {
    id,
    name,
    description,
    ownerType: 'USER',
    ownerId: 'mock-user-id',
    reach: { allAccounts: false, groupCount: 1, userCount: 1 },
    myRole: 'OWNER',
    documentCount: 214,
    sourceType,
    schedule: DAILY,
    lastScheduledRunsFailed: false,
    diagnosticsLocked: false,
    diagnosticsLockToggleable: true,
    createdAt: CREATED,
    updatedAt: CREATED,
  }
}

/** When the connected fixtures were last indexed, as the library list reports it. */
export const SOURCE_LIBRARY_LAST_INDEXED = '2026-10-24T04:02:00Z'

export const SOURCE_LIBRARY_IDS = {
  FILESYSTEM: 'library-filesystem-satzungen',
  CONFLUENCE: 'library-confluence-wiki',
  SHAREPOINT: 'library-sharepoint-rathaus',
  NEXTCLOUD: 'library-nextcloud-projekte',
  SMB: 'library-smb-ablage',
  S3: 'library-s3-protokolle',
  GOOGLE_DRIVE: 'library-gdrive-projekte',
  DROPBOX: 'library-dropbox-projekte',
} as const

export function sourceLibraryDetails(): LibraryResponse[] {
  return [
    {
      ...base(
        SOURCE_LIBRARY_IDS.FILESYSTEM,
        'Satzungen',
        'Ortsrecht vom Dateiserver',
        'FILESYSTEM',
      ),
      sourcePath: '/srv/opaa/satzungen',
      sourceSettings: { excludePatterns: ['Archiv/**', '**/*.tmp'] },
    },
    {
      ...base(
        SOURCE_LIBRARY_IDS.CONFLUENCE,
        'Verwaltungswiki',
        'Spaces aus Confluence Cloud',
        'CONFLUENCE',
      ),
      sourceUrl: 'https://rheinfurt.atlassian.net/wiki',
      sourceCredentialsSet: true,
      sourceSettings: {
        edition: 'CLOUD',
        spaces: [
          { key: 'BAU', name: 'Bauamt' },
          { key: 'RECHT', name: 'Rechtsamt' },
        ],
        fullSyncIntervalDays: 7,
      },
    },
    {
      ...base(
        SOURCE_LIBRARY_IDS.SHAREPOINT,
        'Rathaus-Dokumente',
        'Dokumentbibliotheken der Site Rathaus',
        'SHAREPOINT',
      ),
      sourceUrl: 'https://graph.microsoft.com',
      connectionProfile: {
        id: 'connection-profile-sharepoint',
        name: 'Microsoft 365 Rheinfurt',
        serverUrl: 'https://graph.microsoft.com',
        authMethod: 'CLIENT_CREDENTIALS',
        connectorDefaults: null,
      },
      sourceSettings: {
        libraries: [
          {
            driveId: 'b!rathausDokumente',
            name: 'Rathaus · Dokumente',
            folders: [{ id: '01RATSBESCHLUESSE', name: 'Ratsbeschlüsse' }],
          },
        ],
        fullSyncIntervalDays: 1,
      },
    },
    {
      ...base(
        SOURCE_LIBRARY_IDS.NEXTCLOUD,
        'Projektablage',
        'Gruppenordner der Projekte',
        'NEXTCLOUD',
      ),
      sourceUrl: 'https://cloud.rheinfurt.example',
      sourceCredentialsSet: true,
      sourceSettings: { folders: ['/Projekte', '/Bauamt'] },
    },
    {
      ...base(SOURCE_LIBRARY_IDS.SMB, 'Ablage Dateiserver', 'Windows-Freigabe der Ämter', 'SMB'),
      sourceUrl: 'smb://dateiserver.rheinfurt.example/Ablage',
      sourceCredentialsSet: true,
      sourceSettings: { folders: ['/Bauamt', '/Hauptamt'] },
    },
    {
      ...base(
        SOURCE_LIBRARY_IDS.GOOGLE_DRIVE,
        'Projektordner',
        'Geteilte Ablage des Projektteams',
        'GOOGLE_DRIVE',
      ),
      sourceUrl: 'https://www.googleapis.com',
      sourceCredentialsSet: true,
      sourceSettings: {
        scopes: [
          { drive: '0AMockDrive', name: 'Projektablage' },
          { folder: '1MockFolder', name: 'Freigegebene Akten' },
        ],
        subject: null,
        fullSyncIntervalDays: 7,
      },
    },
    {
      ...base(
        SOURCE_LIBRARY_IDS.DROPBOX,
        'Projektunterlagen',
        'Ordner aus dem Dropbox-Dienstkonto',
        'DROPBOX',
      ),
      sourceUrl: 'https://api.dropboxapi.com',
      connectionProfile: {
        id: 'connection-profile-dropbox-library',
        name: 'Dropbox Rheinfurt',
        serverUrl: 'https://api.dropboxapi.com',
        authMethod: 'OAUTH',
        connectorDefaults: null,
      },
      sourceConnection: {
        accountLabel: 'dienstkonto@rheinfurt.example',
        connectedAt: CREATED,
        responsible: { type: 'USER', id: 'mock-user-id', name: 'Mock User' },
        endedCause: null,
        endedAt: null,
        expiresAt: null,
      },
      sourceSettings: { folders: ['/Projekte', '/Satzungen'], teamSpace: false },
      // the instance default the backend reports for every source with a full reconciliation
      fullSyncIntervalDefaultDays: 7,
    },
  ]
}
