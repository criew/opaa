import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/server'
import { renderWithProviders } from '@/test/test-utils'
import { useLibraryStore } from '../stores/libraryStore'
import { ONEDRIVE_MESSAGES } from '../mocks/oneDriveSourceMock'
import LibrarySourceSection from './LibrarySourceSection'

type User = ReturnType<typeof userEvent.setup>

const profile = {
  id: 'profile-onedrive',
  name: 'Microsoft 365 Rheinfurt',
  serverUrl: 'https://graph.microsoft.com',
  authMethod: 'OAUTH' as const,
}

function oneDrive(drive: Record<string, unknown>) {
  return {
    name: 'Meine Vorlagen',
    sourceType: 'ONEDRIVE',
    sourceUrl: 'https://graph.microsoft.com',
    connectionProfile: profile,
    privateLibrary: true,
    sourceSettings: { drive },
  }
}

function renderSection(drive: Record<string, unknown>) {
  renderWithProviders(
    <LibrarySourceSection libraryId="library-onedrive" library={oneDrive(drive)} canEditSource />,
    { withRouter: true },
  )
}

async function openConnection(user: User) {
  await user.click(screen.getByRole('button', { name: /^Ändern\s*Verbindung$/ }))
  return screen.getByRole('region', { name: 'Verbindung' })
}

describe('LibrarySourceSection - OneDrive (#2467)', () => {
  const update = vi.fn()

  beforeEach(() => {
    update.mockReset().mockResolvedValue(undefined)
    useLibraryStore.setState({ updateExistingLibrary: update })
  })

  it('shows the stored OneDrive and its folders without asking for an address or a secret', async () => {
    const user = userEvent.setup()
    renderSection({
      driveId: 'b!onedriveMaier',
      name: 'OneDrive',
      folders: [{ id: '01VORLAGEN', name: 'Vorlagen' }],
    })

    expect(screen.getByRole('region', { name: 'Umfang' })).toHaveTextContent('Vorlagen')
    const row = await openConnection(user)
    expect(row).toHaveTextContent(/„OneDrive“ – das OneDrive Ihres verbundenen Microsoft-Kontos/)
    expect(within(row).queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByTestId(/sharing-consequence/)).not.toBeInTheDocument()
  })

  it('tests the stored library by its id and names a OneDrive of another account', async () => {
    const user = userEvent.setup()
    renderSection({ driveId: 'b!onedriveFremd', name: 'OneDrive' })

    const row = await openConnection(user)
    await user.click(within(row).getByRole('button', { name: 'Verbindung testen' }))

    expect(await within(row).findByText(/gehört zu einem anderen OneDrive/)).toBeVisible()
    expect(within(row).getByText('Adresse nicht erreichbar')).toBeVisible()
  })

  it('chooses the OneDrive of the connected account anew and drops the folders of the old one', async () => {
    const user = userEvent.setup()
    renderSection({
      driveId: 'b!onedriveFremd',
      name: 'OneDrive',
      folders: [{ id: '01ALT', name: 'Alt' }],
    })

    const row = await openConnection(user)
    await user.click(within(row).getByRole('button', { name: 'OneDrive neu wählen' }))
    await user.click(within(row).getByRole('button', { name: 'Verbindung testen' }))
    expect(await within(row).findByText(ONEDRIVE_MESSAGES.reachable)).toBeVisible()
    await user.click(within(row).getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][1]).toMatchObject({
      sourceUrl: 'https://graph.microsoft.com',
      sourceSettings: { drive: { driveId: 'b!onedriveMaier', name: 'OneDrive' } },
    })
    expect(update.mock.calls[0][1].sourceSettings.drive.folders).toBeUndefined()
    expect(update.mock.calls[0][1].sourceCredentials).toBeUndefined()
  })

  it('names a chosen folder that is gone from the OneDrive', async () => {
    const user = userEvent.setup()
    renderSection({
      driveId: 'b!onedriveMaier',
      name: 'OneDrive',
      folders: [{ id: '01WEG', name: 'Archiv' }],
    })

    const row = await openConnection(user)
    await user.click(within(row).getByRole('button', { name: 'Verbindung testen' }))

    expect(
      await within(row).findByText(
        'Der gewählte Ordner „Archiv“ ist im OneDrive nicht mehr vorhanden; bitte die Ordnerauswahl anpassen.',
      ),
    ).toBeVisible()
  })

  it('says in the selection when a folder is gone from the OneDrive', async () => {
    // the folder vanished between listing it and opening it
    server.use(
      http.post('/api/v1/source-types/ONEDRIVE/browse', async ({ request }) => {
        const body = (await request.json()) as { query?: Record<string, unknown> }
        return HttpResponse.json(
          body.query?.folder
            ? { complete: false, entries: [], message: ONEDRIVE_MESSAGES.folderGone }
            : {
                complete: true,
                entries: [
                  { key: 'folder:01VORLAGEN', name: 'Vorlagen' },
                  { key: 'folder:01WEG', name: 'Verschwunden' },
                ],
              },
        )
      }),
    )
    const user = userEvent.setup()
    renderSection({
      driveId: 'b!onedriveMaier',
      name: 'OneDrive',
      folders: [{ id: '01VORLAGEN', name: 'Vorlagen' }],
    })

    await user.click(screen.getByRole('button', { name: /^Ändern\s*Umfang$/ }))
    await user.click(await screen.findByRole('button', { name: 'Ordner auswählen …' }))
    const dialog = await screen.findByRole('dialog', { name: 'Ordner auswählen' })
    await user.click(await within(dialog).findByRole('button', { name: '„Verschwunden“ öffnen' }))

    expect(
      await within(dialog).findByText(/Der Ordner ist im OneDrive nicht mehr vorhanden\./),
    ).toBeVisible()
  })
})
