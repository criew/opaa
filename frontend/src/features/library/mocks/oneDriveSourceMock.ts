import { HttpResponse } from 'msw'
import type { SourceBrowseResponse } from '@/shared/api/types'
import { mockOwnOneDrive, type MockOneDriveFolder } from './libraryFixtures'

/** The wording of OneDriveSourceConnector, OwnDrive and DriveRule.ONEDRIVE, so the mock answers alike. */
export const ONEDRIVE_MESSAGES = {
  browseNeedsAccount: 'Für die Auflistung ist ein verbundenes Microsoft-Konto erforderlich',
  testNeedsAccount: 'Bitte zuerst das Microsoft-Konto verbinden.',
  testNeedsDrive: 'Bitte das OneDrive wählen.',
  reachable: 'Anmeldung erfolgreich; das OneDrive ist erreichbar.',
  notBusiness:
    'Das verbundene Microsoft-Konto hat kein OneDrive for Business. Private Microsoft-Konten werden noch nicht unterstützt; bitte mit dem Arbeits- oder Schulkonto verbinden.',
  otherDrive:
    'Das verbundene Microsoft-Konto gehört zu einem anderen OneDrive als dem dieser Bibliothek. Bitte mit dem Konto verbinden, dem das OneDrive gehört, oder das OneDrive neu wählen.',
  noDrive: 'Für das verbundene Microsoft-Konto ist kein OneDrive bereitgestellt.',
  notReadable:
    'Das verbundene Microsoft-Konto darf sein OneDrive nicht lesen; die Anwendung braucht die delegierte Berechtigung Files.Read.',
  folderGone: 'Der Ordner ist im OneDrive nicht mehr vorhanden.',
  folderMissing: (name: string) =>
    `Der gewählte Ordner „${name}“ ist im OneDrive nicht mehr vorhanden; bitte die Ordnerauswahl anpassen.`,
} as const

interface ProbeBody {
  connectionProfileId?: string | null
  libraryId?: string | null
}

/** Whether the request runs on a connected account: through a profile or a stored library. */
function onAccount(body: ProbeBody) {
  return Boolean(body.connectionProfileId || body.libraryId)
}

function findFolder(folders: MockOneDriveFolder[], id: string): MockOneDriveFolder | null {
  for (const folder of folders) {
    if (folder.id === id) return folder
    const below = findFolder(folder.children, id)
    if (below) return below
  }
  return null
}

function refused(message: string) {
  return HttpResponse.json({ complete: false, entries: [], message } satisfies SourceBrowseResponse)
}

/**
 * POST /source-types/ONEDRIVE/browse like the connector: without parameters the one OneDrive of
 * the account, with `drive` and an optional `folder` the folders below - only of that OneDrive.
 */
export function browseOneDrive(body: ProbeBody & { query?: Record<string, unknown> | null }) {
  if (!onAccount(body)) {
    return HttpResponse.json(
      { error: ONEDRIVE_MESSAGES.browseNeedsAccount, status: 400 },
      { status: 400 },
    )
  }
  const query = body.query ?? {}
  if (typeof query.drive !== 'string') {
    return HttpResponse.json({
      complete: true,
      entries: [{ key: `drive:${mockOwnOneDrive.id}`, name: mockOwnOneDrive.name }],
      message: null,
    } satisfies SourceBrowseResponse)
  }
  if (query.drive !== mockOwnOneDrive.id) return refused(ONEDRIVE_MESSAGES.otherDrive)
  let level: MockOneDriveFolder[] = mockOwnOneDrive.folders
  if (typeof query.folder === 'string') {
    const folder = findFolder(mockOwnOneDrive.folders, query.folder)
    if (!folder) return refused(ONEDRIVE_MESSAGES.folderGone)
    level = folder.children
  }
  return HttpResponse.json({
    complete: true,
    entries: level.map((folder) => ({ key: `folder:${folder.id}`, name: folder.name })),
    message: null,
  } satisfies SourceBrowseResponse)
}

interface OneDriveSetting {
  driveId?: string
  folders?: Array<string | { id?: string; name?: string | null }>
}

/**
 * POST /libraries/source-test for ONEDRIVE like OneDriveSourceConnector#testConnection: the
 * account's own OneDrive, then every chosen folder present.
 */
export function testOneDrive(
  body: ProbeBody & { sourceSettings?: Record<string, unknown> | null },
) {
  if (!onAccount(body)) {
    return HttpResponse.json({
      reachable: false,
      credentialsVerified: null,
      message: ONEDRIVE_MESSAGES.testNeedsAccount,
    })
  }
  const drive = body.sourceSettings?.drive as OneDriveSetting | undefined
  if (!drive?.driveId) {
    return HttpResponse.json({
      reachable: false,
      credentialsVerified: null,
      message: ONEDRIVE_MESSAGES.testNeedsDrive,
    })
  }
  // the generated folders of the fixture scenarios (sourceScenarioMock) all lie in the own OneDrive
  const generated = (id: string) => id.startsWith(`${mockOwnOneDrive.id}_F`)
  if (drive.driveId !== mockOwnOneDrive.id) {
    return HttpResponse.json({
      reachable: false,
      credentialsVerified: true,
      message: ONEDRIVE_MESSAGES.otherDrive,
    })
  }
  for (const folder of drive.folders ?? []) {
    const id = typeof folder === 'string' ? folder : (folder.id ?? '')
    const shown = typeof folder === 'string' ? id : folder.name || id
    if (!generated(id) && !findFolder(mockOwnOneDrive.folders, id)) {
      return HttpResponse.json({
        reachable: false,
        credentialsVerified: true,
        message: ONEDRIVE_MESSAGES.folderMissing(shown),
      })
    }
  }
  return HttpResponse.json({
    reachable: true,
    credentialsVerified: true,
    message: ONEDRIVE_MESSAGES.reachable,
  })
}
