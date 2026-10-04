import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../mocks/server'
import { mockSourceTypes } from '../../mocks/libraryFixtures'
import { useLibraryStore } from '../../stores/libraryStore'
import { answerConfirm, renderWithProviders } from '../../test/test-utils'
import type { ConnectionProfileOption, SourceConnectionTestRequest } from '../../types/api'
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
  const internRef = {
    id: intern.id,
    name: intern.name,
    serverUrl: intern.serverUrl,
    authMethod: intern.authMethod,
  }
  const connect = vi.fn()
  const release = vi.fn()
  let probes: SourceConnectionTestRequest[] = []

  beforeEach(() => {
    connect.mockReset().mockResolvedValue(undefined)
    release.mockReset().mockResolvedValue(undefined)
    useLibraryStore.setState({
      connectLibraryToProfile: connect,
      releaseLibraryFromProfile: release,
    })
    probes = []
    server.use(
      http.get('/api/v1/connection-profiles', () => HttpResponse.json([intern, neu])),
      http.post('/api/v1/libraries/source-test', async ({ request }) => {
        probes.push((await request.json()) as SourceConnectionTestRequest)
        return HttpResponse.json({
          reachable: true,
          credentialsVerified: true,
          message: 'Verbindung hergestellt; 1 Ordner lesbar.',
        })
      }),
    )
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

    // the frozen address lies under no profile: the dialog asks for one under the chosen profile
    expect(within(dialog).getByText(/liegt nicht unter dem Zugang „Nextcloud neu“/)).toBeVisible()
    const address = within(dialog).getByLabelText('Neue Adresse')
    expect(address).toHaveValue('https://cloud.neu.example')
    await user.clear(address)
    await user.type(address, 'https://cloud.anders.example/svc')
    expect(within(dialog).getByRole('button', { name: 'Prüfen und zuordnen' })).toBeDisabled()
    await user.clear(address)
    await user.type(address, 'https://cloud.neu.example/remote.php/dav/files/svc')
    await user.click(within(dialog).getByRole('button', { name: 'Prüfen und zuordnen' }))

    await waitFor(() =>
      expect(connect).toHaveBeenCalledWith(
        'library-1',
        'profile-neu',
        'https://cloud.neu.example/remote.php/dav/files/svc',
      ),
    )
    expect(probes).toEqual([
      expect.objectContaining({
        sourceType: 'NEXTCLOUD',
        sourceUrl: 'https://cloud.neu.example/remote.php/dav/files/svc',
        libraryId: 'library-1',
        connectionProfileId: 'profile-neu',
      }),
    ])
  }, 15000)

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
        library={{ ...nextcloud, connectionProfile: internRef }}
        canEditSource
      />,
    )

    await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
    expect(await within(dialog).findByRole('radio', { name: /Nextcloud neu/ })).toBeChecked()
    expect(
      within(dialog).queryByRole('radio', { name: /Nextcloud intern/ }),
    ).not.toBeInTheDocument()
    // the address under the previous profile moves under the new one
    expect(within(dialog).getByTestId('library-connection-address')).toHaveTextContent(
      'https://cloud.neu.example/remote.php/dav/files/svc',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Prüfen und zuordnen' }))

    await waitFor(() =>
      expect(connect).toHaveBeenCalledWith(
        'library-1',
        'profile-neu',
        'https://cloud.neu.example/remote.php/dav/files/svc',
      ),
    )
    expect(probes[0]).toMatchObject({ libraryId: 'library-1', connectionProfileId: 'profile-neu' })
    expect(await screen.findByText(/Verbindung hergestellt; 1 Ordner lesbar/)).toBeVisible()
  })

  it('tests the new profile before switching and saves after a failed test only on request', async () => {
    server.use(
      http.post('/api/v1/libraries/source-test', async ({ request }) => {
        probes.push((await request.json()) as SourceConnectionTestRequest)
        return HttpResponse.json({
          reachable: false,
          credentialsVerified: false,
          message: 'Nextcloud hat Benutzername oder App-Passwort abgelehnt (HTTP 401).',
        })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...nextcloud, connectionProfile: internRef }}
        canEditSource
      />,
    )

    await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
    await within(dialog).findByRole('radio', { name: /Nextcloud neu/ })
    await user.click(within(dialog).getByRole('button', { name: 'Prüfen und zuordnen' }))

    expect(await within(dialog).findByTestId('library-connection-test')).toHaveTextContent(
      /App-Passwort abgelehnt/,
    )
    expect(connect).not.toHaveBeenCalled()
    expect(
      within(dialog).queryByRole('button', { name: 'Prüfen und zuordnen' }),
    ).not.toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Trotzdem zuordnen' }))
    await waitFor(() =>
      expect(connect).toHaveBeenCalledWith(
        'library-1',
        'profile-neu',
        'https://cloud.neu.example/remote.php/dav/files/svc',
      ),
    )
    expect(probes).toHaveLength(1)
  })

  it('shows the result of „Verbindung prüfen“ without saving, and an error of the test itself', async () => {
    server.use(
      http.post('/api/v1/libraries/source-test', () =>
        HttpResponse.json(
          { error: 'Der Zugang „Nextcloud neu“ ist gesperrt.', status: 403 },
          { status: 403 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...nextcloud, connectionProfile: internRef }}
        canEditSource
      />,
    )

    await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
    await within(dialog).findByRole('radio', { name: /Nextcloud neu/ })
    await user.click(within(dialog).getByRole('button', { name: 'Verbindung prüfen' }))

    expect(await within(dialog).findByTestId('library-connection-test')).toHaveTextContent(
      'Der Zugang „Nextcloud neu“ ist gesperrt.',
    )
    expect(within(dialog).getByRole('button', { name: 'Trotzdem zuordnen' })).toBeVisible()
    expect(connect).not.toHaveBeenCalled()
  })

  it('lets a manager without the right to create a library pick among the profiles of the library', async () => {
    const asked: string[] = []
    server.use(
      http.get('/api/v1/connection-profiles', ({ request }) => {
        asked.push(request.url)
        const url = new URL(request.url)
        if (url.searchParams.get('libraryId') !== 'library-1') {
          return HttpResponse.json({ error: 'Keine Berechtigung' }, { status: 403 })
        }
        return HttpResponse.json([
          intern,
          {
            ...neu,
            creatable: false,
            creationNotice:
              'Der Zugang „Nextcloud neu“ ist für Sie nicht freigegeben. Zuständig ist die Systemverwaltung.',
          },
        ])
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...nextcloud, connectionProfile: internRef }}
        canEditSource
      />,
    )

    await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
    expect(await within(dialog).findByText(/ist für Sie nicht freigegeben/)).toBeVisible()
    expect(within(dialog).getByRole('radio', { name: /Nextcloud neu/ })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: 'Prüfen und zuordnen' })).toBeDisabled()
    expect(asked.every((url) => url.includes('libraryId=library-1'))).toBe(true)
  })

  it('releases a library from its profile after a confirmation', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...nextcloud, connectionProfile: internRef }}
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
          connectionProfile: internRef,
        }}
        canEditSource
      />,
    )

    await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
    await within(dialog).findByRole('radio', { name: /Nextcloud neu/ })
    await user.click(within(dialog).getByRole('button', { name: 'Prüfen und zuordnen' }))

    expect(await screen.findByText(/hinterlegten Zugangsdaten verworfen/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Quelle bearbeiten' }))
    expect(
      await screen.findByRole('dialog', { name: 'Quellkonfiguration bearbeiten' }),
    ).toBeVisible()
  })
})

describe('LibrarySourceSection - verbundenes Konto der Besitzerin', () => {
  const nextcloud = { name: 'Meine Ablage', sourceType: 'NEXTCLOUD' }
  const expiredNotice =
    'Abgelaufen: Die Anmeldung beim Zugang „Zugang Nextcloud intern“ ist abgelaufen oder wurde vom Anbieter abgelehnt. Die Besitzerin verbindet ihr Konto auf der Seite „Verbundene Konten“ neu.'

  function overviewWith(usedByLibraryId: string) {
    return {
      accounts: [
        {
          profileId: 'profile-1',
          profileName: 'Zugang Nextcloud intern',
          authMethod: 'PERSONAL_SECRET',
          secretForm: 'USERNAME_AND_PASSWORD',
          state: 'EXPIRED',
          accountLabel: 'avogt',
          released: true,
          reconnectable: true,
          connectedAt: '2026-09-20T08:00:00Z',
          usedBy: [{ id: usedByLibraryId, name: 'Meine Ablage' }],
        },
      ],
      connectable: [],
      missingAccess: { responsible: 'Systemverwaltung', text: '…' },
    }
  }

  it('leads the owner of a private library to "Verbundene Konten" when her account expired', async () => {
    server.use(
      http.get('/api/v1/me/connected-accounts', () => HttpResponse.json(overviewWith('library-1'))),
    )
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          sourceBlock: {
            reason: 'EXPIRED',
            responsible: 'Besitzerin der Bibliothek',
            notice: expiredNotice,
          },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    const notice = screen.getByTestId('source-lock-notice')
    expect(notice).toHaveTextContent(expiredNotice)
    expect(
      await within(notice).findByRole('link', { name: 'Konto neu verbinden' }),
    ).toHaveAttribute('href', '/settings/accounts')
  })

  it('offers no account action for a library that does not run on the own account', async () => {
    let asked = false
    server.use(
      http.get('/api/v1/me/connected-accounts', () => {
        asked = true
        return HttpResponse.json(overviewWith('another-library'))
      }),
    )
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          sourceBlock: {
            reason: 'NOT_CONNECTED',
            responsible: 'Verwaltende der Bibliothek',
            notice: 'Verbindung getrennt: Für den Zugang sind keine Zugangsdaten hinterlegt.',
          },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    await waitFor(() => expect(asked).toBe(true))
    expect(
      within(screen.getByTestId('source-lock-notice')).queryByRole('link'),
    ).not.toBeInTheDocument()
  })

  it.each(['OWNER_DEACTIVATED', 'DORMANT'] as const)(
    'shows the notice of %s without an action and without asking for accounts',
    (reason) => {
      let asked = false
      server.use(
        http.get('/api/v1/me/connected-accounts', () => {
          asked = true
          return HttpResponse.json(overviewWith('library-1'))
        }),
      )
      renderWithProviders(
        <LibrarySourceSection
          libraryId="library-1"
          library={{
            ...nextcloud,
            sourceBlock: { reason, responsible: 'Systemverwaltung', notice: `Hinweis ${reason}` },
          }}
          canEditSource
        />,
        { withRouter: true },
      )

      const notice = screen.getByTestId('source-lock-notice')
      expect(notice).toHaveTextContent(`Hinweis ${reason}`)
      expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
      expect(asked).toBe(false)
    },
  )
})
