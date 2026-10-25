import { MOCK_SCENARIOS, type MockScenario } from '@/shared/mocks/mockScenario'

export const SCENARIO_LABELS: Record<MockScenario, string> = {
  default: 'Standard',
  small: 'Klein',
  scale: 'Viele Einträge',
  empty: 'Leer',
  slow: 'Langsam',
  failed: 'Fehler',
  partial: 'Teilfehler',
}

export const SCENARIOS = MOCK_SCENARIOS.map((id) => ({ id, label: SCENARIO_LABELS[id] }))

export interface PrototypeEntry {
  label: string
  path: string
}

/**
 * Entry points of the prototype bar. The mocked user is always the system administrator; what a
 * reader sees comes from libraries whose fixture carries `myRole: 'VIEWER'`.
 */
export const ENTRY_GROUPS: { label: string; entries: PrototypeEntry[] }[] = [
  {
    label: 'Bibliothek je Quellart (Verwaltung)',
    entries: [
      { label: 'Dateisystem', path: '/libraries/library-filesystem-satzungen' },
      { label: 'Confluence', path: '/libraries/library-confluence-wiki' },
      { label: 'SharePoint', path: '/libraries/library-sharepoint-rathaus' },
      { label: 'OneDrive', path: '/libraries/library-onedrive-vorlagen' },
      { label: 'Nextcloud', path: '/libraries/library-nextcloud-projekte' },
      { label: 'SMB', path: '/libraries/library-smb-ablage' },
      { label: 'S3', path: '/libraries/library-s3-protokolle' },
      { label: 'Google Drive', path: '/libraries/library-gdrive-projekte' },
      { label: 'Dropbox', path: '/libraries/library-dropbox-projekte' },
      { label: 'Hochgeladene Dokumente', path: '/libraries/library-mine' },
    ],
  },
  {
    label: 'Bibliothek als Leser',
    entries: [{ label: 'Dienstanweisungen', path: '/libraries/library-dienstanweisungen' }],
  },
  {
    label: 'Seiten',
    entries: [
      { label: 'Katalog', path: '/catalog' },
      { label: 'Neue Bibliothek', path: '/libraries/new' },
      { label: 'Spaces', path: '/spaces' },
      { label: 'Space Engineering', path: '/spaces/space-engineering' },
      { label: 'Chat', path: '/chat' },
      { label: 'Anbindungen', path: '/admin/connections' },
      { label: 'Benutzer', path: '/admin/users' },
      { label: 'Gruppen', path: '/admin/groups' },
      { label: 'Suche & Indexierung', path: '/admin/search' },
      { label: 'Einstellungen', path: '/settings' },
    ],
  },
]

/** Search parameters of the prototype; all other parameters belong to the app. */
export const WIDTH_PARAM = 'protoWidth'
export const FRAME_PARAM = 'protoFrame'
export const NARROW_WIDTH = 320
