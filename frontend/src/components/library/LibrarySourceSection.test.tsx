import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../mocks/server'
import { mockSourceTypes } from '../../mocks/libraryFixtures'
import { useLibraryStore } from '../../stores/libraryStore'
import { answerConfirm, renderWithProviders } from '../../test/test-utils'
import type { ConnectionProfileOption } from '../../types/api'
import LibrarySourceSection from './LibrarySourceSection'

const library = {
  name: 'Wiki',
  sourceType: 'CONFLUENCE',
  sourceUrl: 'https://wiki.rheinfurt.example',
}

describe('LibrarySourceSection - Zugang (#2160)', () => {
  it('names the connection profile of the library', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...library, connectionProfile: { id: 'p-1', name: 'Zugang Wiki intern' } }}
        canEditSource
      />,
    )

    expect(screen.getByTestId('connection-profile')).toHaveTextContent('Zugang: Zugang Wiki intern')
    expect(screen.queryByTestId('connection-profile-removed')).not.toBeInTheDocument()
  })

  it('tells every reader that the profile was removed, who acts and what happens to the content', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...library, connectionProfileRemoved: true }}
        canEditSource={false}
      />,
    )

    expect(screen.getByTestId('connection-profile-removed')).toHaveTextContent(
      /Zugang entfernt.*bleibt durchsuchbar.*Verwaltenden der Bibliothek/,
    )
  })

  it('shows the lock notice of a locked source to every reader', () => {
    const notice =
      'Gesperrt – Inhalt wird nicht mehr aktualisiert. Die Systemverwaltung hat den Zugang „Wiki“ gesperrt; der vorhandene Inhalt bleibt durchsuchbar. Zuständig ist die Systemverwaltung.'
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...library,
          sourceBlock: { reason: 'PROFILE_LOCKED', responsible: 'Systemverwaltung', notice },
        }}
        canEditSource={false}
      />,
    )

    expect(screen.getByTestId('source-lock-notice')).toHaveTextContent(notice)
  })
})

describe('LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162)', () => {
  const nextcloud = {
    name: 'Projektablage',
    sourceType: 'NEXTCLOUD',
    sourceUrl: 'https://cloud.intern.example/remote.php/dav/files/svc',
  }
  const intern: ConnectionProfileOption = {
    id: 'profile-intern',
    name: 'Nextcloud intern',
    sourceType: 'NEXTCLOUD',
    serverUrl: 'https://cloud.intern.example',
    authMethod: 'PERSONAL_SECRET',
    sourceInsecureSsl: false,
    creatable: true,
  }
  const neu: ConnectionProfileOption = {
    ...intern,
    id: 'profile-neu',
    name: 'Nextcloud neu',
    serverUrl: 'https://cloud.neu.example',
  }
  const connect = vi.fn()
  const release = vi.fn()

  beforeEach(() => {
    connect.mockReset().mockResolvedValue(undefined)
    release.mockReset().mockResolvedValue(undefined)
    useLibraryStore.setState({
      connectLibraryToProfile: connect,
      releaseLibraryFromProfile: release,
    })
    server.use(http.get('/api/v1/connection-profiles', () => HttpResponse.json([intern, neu])))
  })

  it('offers „Zugang zuordnen“ on the lock of the own address and connects through the chosen profile', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          sourceBlock: {
            reason: 'PROFILE_REQUIRED',
            responsible: 'Verwaltende der Bibliothek',
            notice:
              'Gesperrt – Inhalt wird nicht mehr aktualisiert. Die Quellart ist nur über Zugänge nutzbar.',
          },
        }}
        canEditSource
      />,
    )

    const notice = screen.getByTestId('source-lock-notice')
    await user.click(within(notice).getByRole('button', { name: 'Zugang zuordnen' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang zuordnen' })
    await user.click(await within(dialog).findByRole('radio', { name: /Nextcloud neu/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Zuordnen' }))

    await waitFor(() => expect(connect).toHaveBeenCalledWith('library-1', 'profile-neu'))
  })

  it('offers no assignment on a lock that a profile does not lift, nor to a reader', () => {
    const { unmount } = renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          sourceBlock: {
            reason: 'TYPE_LOCKED',
            responsible: 'Systemverwaltung',
            notice: 'Gesperrt',
          },
        }}
        canEditSource
      />,
    )
    expect(
      within(screen.getByTestId('source-lock-notice')).queryByRole('button'),
    ).not.toBeInTheDocument()
    unmount()

    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...nextcloud, connectionProfileRemoved: true }}
        canEditSource={false}
      />,
    )
    expect(
      within(screen.getByTestId('connection-profile-removed')).queryByRole('button'),
    ).not.toBeInTheDocument()
  })

  it('offers the way back after „Zugang entfernt“', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...nextcloud, connectionProfileRemoved: true }}
        canEditSource
      />,
    )

    const removed = screen.getByTestId('connection-profile-removed')
    await user.click(within(removed).getByRole('button', { name: 'Zugang zuordnen' }))
    expect(await screen.findByRole('dialog', { name: 'Zugang zuordnen' })).toBeVisible()
  })

  it('switches a connected library to another profile, never offering the current one', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...nextcloud, connectionProfile: { id: intern.id, name: intern.name } }}
        canEditSource
      />,
    )

    await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
    expect(await within(dialog).findByRole('radio', { name: /Nextcloud neu/ })).toBeChecked()
    expect(
      within(dialog).queryByRole('radio', { name: /Nextcloud intern/ }),
    ).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Zuordnen' }))

    await waitFor(() => expect(connect).toHaveBeenCalledWith('library-1', 'profile-neu'))
  })

  it('releases a library from its profile after a confirmation', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...nextcloud, connectionProfile: { id: intern.id, name: intern.name } }}
        canEditSource
      />,
    )

    await user.click(await screen.findByRole('button', { name: 'Zugang lösen' }))
    await answerConfirm(user, /vom Zugang „Nextcloud intern“ lösen\?/, 'Abbrechen')
    expect(release).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Zugang lösen' }))
    await answerConfirm(user, /vom Zugang „Nextcloud intern“ lösen\?/, 'Lösen')
    await waitFor(() => expect(release).toHaveBeenCalledWith('library-1'))
  })

  it('offers no release while the type is usable only through profiles', async () => {
    server.use(
      http.get('/api/v1/source-types', () =>
        HttpResponse.json(
          mockSourceTypes.map((d) =>
            d.type === 'NEXTCLOUD'
              ? { ...d, profileRequired: true, creatableWithOwnAddress: false }
              : d,
          ),
        ),
      ),
    )
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...nextcloud, connectionProfile: { id: intern.id, name: intern.name } }}
        canEditSource
      />,
    )

    expect(await screen.findByRole('button', { name: 'Zugang wechseln' })).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Zugang lösen' })).not.toBeInTheDocument()
  })

  it('offers „Zugang zuordnen“ for a library with its own address of a type with profiles', async () => {
    renderWithProviders(
      <LibrarySourceSection libraryId="library-1" library={nextcloud} canEditSource />,
    )

    expect(await screen.findByRole('button', { name: 'Zugang zuordnen' })).toBeVisible()
    expect(screen.getByTestId('connection-profile')).toHaveTextContent(
      'Zugang: keiner, eigene Adresse',
    )
  })

  it('names a deleted profile as removed, not as an own address', async () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...nextcloud, connectionProfileRemoved: true }}
        canEditSource
      />,
    )

    expect(await screen.findByTestId('connection-profile')).toHaveTextContent('Zugang: entfernt')
  })

  it('says when a switch to another server discarded the secret and offers to edit the source', async () => {
    connect.mockResolvedValue({ sourceCredentialsSet: false })
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          sourceCredentialsSet: true,
          connectionProfile: { id: intern.id, name: intern.name },
        }}
        canEditSource
      />,
    )

    await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
    await within(dialog).findByRole('radio', { name: /Nextcloud neu/ })
    await user.click(within(dialog).getByRole('button', { name: 'Zuordnen' }))

    expect(await screen.findByText(/hinterlegten Zugangsdaten verworfen/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Quelle bearbeiten' }))
    expect(
      await screen.findByRole('dialog', { name: 'Quellkonfiguration bearbeiten' }),
    ).toBeVisible()
  })
})
