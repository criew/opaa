import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/server'
import { mockSourceTypes } from '../mocks/libraryFixtures'
import { useLibraryStore } from '../stores/libraryStore'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import type { ConnectionProfileOption, SourceConnectionTestRequest } from '@/shared/api/types'
import LibrarySourceSection from './LibrarySourceSection'

/** The Zugang and its actions live in the row „Verbindung“, behind its „Ändern“. */
async function openConnection(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /^Ändern\s*Verbindung$/ }))
}

const library = {
  name: 'Wiki',
  sourceType: 'CONFLUENCE',
  sourceUrl: 'https://wiki.rheinfurt.example',
}

describe('LibrarySourceSection - Zugang (#2160)', () => {
  it('names the connection profile of the library', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...library, connectionProfile: { id: 'p-1', name: 'Zugang Wiki intern' } }}
        canEditSource
      />,
    )

    const row = screen.getByRole('region', { name: 'Verbindung' })
    expect(row).toHaveTextContent('Zugang „Zugang Wiki intern“')
    await openConnection(user)
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
    ownership: 'LIBRARY',
    ownAccount: false,
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
            action: 'ASSIGN_PROFILE',
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

    await openConnection(user)
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

    await openConnection(user)
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

    await openConnection(user)
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

  it('offers a manager only the profiles released to her, asking with the library', async () => {
    const asked: string[] = []
    server.use(
      http.get('/api/v1/connection-profiles', ({ request }) => {
        asked.push(request.url)
        const url = new URL(request.url)
        if (url.searchParams.get('libraryId') !== 'library-1') {
          return HttpResponse.json({ error: 'Keine Berechtigung' }, { status: 403 })
        }
        // „Nextcloud neu“ is not released to her: the server does not list it
        return HttpResponse.json([intern])
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

    await openConnection(user)
    await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
    expect(
      await within(dialog).findByText(/steht Ihnen kein weiterer Zugang zur Verfügung/),
    ).toBeVisible()
    expect(within(dialog).queryByRole('radio')).not.toBeInTheDocument()
    expect(within(dialog).queryByText(/nicht freigegeben/)).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Prüfen und zuordnen' })).toBeDisabled()
    expect(asked.every((url) => url.includes('libraryId=library-1'))).toBe(true)
  })

  it('keeps the status of a library whose profile is locked, with its reason and the way out', async () => {
    const notice =
      'Gesperrt – Inhalt wird nicht mehr aktualisiert. Die Systemverwaltung hat den Zugang „Nextcloud intern“ gesperrt; der vorhandene Inhalt bleibt durchsuchbar. Zuständig ist die Systemverwaltung.'
    // a locked profile is no option anymore - the library still names it and its lock
    server.use(http.get('/api/v1/connection-profiles', () => HttpResponse.json([neu])))
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          connectionProfile: internRef,
          sourceBlock: { reason: 'PROFILE_LOCKED', responsible: 'Systemverwaltung', notice },
        }}
        canEditSource
      />,
    )

    expect(screen.getByTestId('source-lock-notice')).toHaveTextContent(notice)
    await openConnection(user)
    expect(screen.getByTestId('connection-profile')).toHaveTextContent('Nextcloud intern')
    await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
    expect(await within(dialog).findByRole('radio', { name: /Nextcloud neu/ })).toBeChecked()
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

    await openConnection(user)
    await user.click(await screen.findByRole('button', { name: 'Zugang lösen' }))
    await answerConfirm(user, /vom Zugang „Nextcloud intern“ lösen\?/, 'Abbrechen')
    expect(release).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Zugang lösen' }))
    await answerConfirm(user, /vom Zugang „Nextcloud intern“ lösen\?/, 'Lösen')
    await waitFor(() => expect(release).toHaveBeenCalledWith('library-1'))
  })

  it('offers no release while the type is usable only through profiles', async () => {
    const user = userEvent.setup()
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

    await openConnection(user)
    expect(await screen.findByRole('button', { name: 'Zugang wechseln' })).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Zugang lösen' })).not.toBeInTheDocument()
  })

  it('offers „Zugang zuordnen“ for a library with its own address of a type with profiles', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection libraryId="library-1" library={nextcloud} canEditSource />,
    )

    await openConnection(user)
    expect(await screen.findByRole('button', { name: 'Zugang zuordnen' })).toBeVisible()
    expect(screen.getByTestId('connection-profile')).toHaveTextContent(
      'Zugang: keiner, eigene Adresse',
    )
  })

  it('names a deleted profile as removed, not as an own address', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...nextcloud, connectionProfileRemoved: true }}
        canEditSource
      />,
    )

    await openConnection(user)
    expect(await screen.findByTestId('connection-profile')).toHaveTextContent('Zugang: entfernt')
  })

  describe('private library (#2164)', () => {
    const ownNeu: ConnectionProfileOption = { ...neu, ownership: 'BOTH', ownAccount: true }
    const third: ConnectionProfileOption = {
      ...intern,
      id: 'profile-dritt',
      name: 'Nextcloud dritt',
      serverUrl: 'https://cloud.dritt.example',
    }
    const forPersons: ConnectionProfileOption = {
      ...intern,
      id: 'profile-personen',
      name: 'Nextcloud Personen',
      serverUrl: 'https://cloud.personen.example',
      ownership: 'PERSON',
      ownAccount: true,
    }

    beforeEach(() => {
      server.use(
        http.get('/api/v1/connection-profiles', () =>
          HttpResponse.json([intern, ownNeu, third, forPersons]),
        ),
      )
    })

    it('offers no release from the profile', async () => {
      const user = userEvent.setup()
      renderWithProviders(
        <LibrarySourceSection
          libraryId="library-1"
          library={{ ...nextcloud, connectionProfile: internRef, privateLibrary: true }}
          canEditSource
        />,
      )

      await openConnection(user)
      expect(await screen.findByRole('button', { name: 'Zugang wechseln' })).toBeVisible()
      expect(screen.queryByRole('button', { name: 'Zugang lösen' })).not.toBeInTheDocument()
    })

    it('offers only the profiles the owner has a connected account on, one for persons only included', async () => {
      const user = userEvent.setup()
      renderWithProviders(
        <LibrarySourceSection
          libraryId="library-1"
          library={{ ...nextcloud, connectionProfile: internRef, privateLibrary: true }}
          canEditSource
        />,
      )

      await openConnection(user)
      await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
      const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
      expect(
        await within(dialog).findByRole('radio', { name: /Nextcloud neu/ }),
      ).toBeInTheDocument()
      expect(within(dialog).getByRole('radio', { name: /Nextcloud Personen/ })).toBeInTheDocument()
      expect(within(dialog).queryByRole('radio', { name: /Nextcloud dritt/ })).toBeNull()
      expect(dialog).not.toHaveTextContent(/Zugangsdaten verworfen/)
    })

    it('never offers a profile for persons only to a shared library', async () => {
      const user = userEvent.setup()
      renderWithProviders(
        <LibrarySourceSection
          libraryId="library-1"
          library={{ ...nextcloud, connectionProfile: internRef }}
          canEditSource
        />,
      )

      await openConnection(user)
      await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
      const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
      expect(
        await within(dialog).findByRole('radio', { name: /Nextcloud neu/ }),
      ).toBeInTheDocument()
      expect(within(dialog).queryByRole('radio', { name: /Nextcloud Personen/ })).toBeNull()
      expect(within(dialog).queryByText(/privat/)).toBeNull()
    })

    it('leads to "Verbundene Konten" when no other profile carries an account of the owner', async () => {
      server.use(
        http.get('/api/v1/connection-profiles', () => HttpResponse.json([intern, neu, third])),
      )
      const user = userEvent.setup()
      renderWithProviders(
        <LibrarySourceSection
          libraryId="library-1"
          library={{ ...nextcloud, connectionProfile: internRef, privateLibrary: true }}
          canEditSource
        />,
        { withRouter: true },
      )

      await openConnection(user)
      await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
      const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
      const none = await within(dialog).findByTestId('library-connection-connection-none')
      expect(within(none).getByRole('link', { name: 'Verbundene Konten' })).toHaveAttribute(
        'href',
        '/settings/accounts',
      )
      expect(dialog).not.toHaveTextContent(/Systemverwaltung/)
    })
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

    await openConnection(user)
    await user.click(await screen.findByRole('button', { name: 'Zugang wechseln' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang wechseln' })
    await within(dialog).findByRole('radio', { name: /Nextcloud neu/ })
    await user.click(within(dialog).getByRole('button', { name: 'Prüfen und zuordnen' }))

    expect(await screen.findByText(/hinterlegten Zugangsdaten verworfen/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Quelle bearbeiten' }))
    // the fields of „Verbindung“ take the secret anew
    const row = screen.getByRole('region', { name: 'Verbindung' })
    expect(within(row).getByRole('button', { name: 'Zugangsdaten ersetzen' })).toBeVisible()
  })
})

describe('LibrarySourceSection - Aktion am Sperrhinweis', () => {
  const nextcloud = { name: 'Meine Ablage', sourceType: 'NEXTCLOUD' }
  const expiredNotice =
    'Abgelaufen: Die Anmeldung beim Zugang „Zugang Nextcloud intern“ ist abgelaufen oder wurde vom Anbieter abgelehnt. Die Besitzerin verbindet ihr Konto auf der Seite „Verbundene Konten“ neu.'

  it('leads the owner of a private library to "Verbundene Konten" when her account expired', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          privateLibrary: true,
          sourceBlock: {
            reason: 'EXPIRED',
            responsible: 'Besitzerin der Bibliothek',
            notice: expiredNotice,
            action: 'CONNECT_OWN_ACCOUNT',
          },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    const notice = screen.getByTestId('source-lock-notice')
    expect(notice).toHaveTextContent(expiredNotice)
    expect(within(notice).getByRole('link', { name: 'Konto neu verbinden' })).toHaveAttribute(
      'href',
      '/settings/accounts',
    )
  })

  it('leads to "Verbundene Konten" from the action alone, without asking for accounts', () => {
    let asked = false
    server.use(
      http.get('/api/v1/me/connected-accounts', () => {
        asked = true
        return HttpResponse.json({})
      }),
    )
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          privateLibrary: true,
          sourceBlock: {
            reason: 'NOT_CONNECTED',
            responsible: 'Besitzerin der Bibliothek',
            notice: 'Verbindung getrennt: Ihr Konto ist nicht verbunden.',
            action: 'CONNECT_OWN_ACCOUNT',
          },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    const notice = screen.getByTestId('source-lock-notice')
    expect(within(notice).getByRole('link', { name: 'Konto verbinden' })).toHaveAttribute(
      'href',
      '/settings/accounts',
    )
    expect(asked).toBe(false)
  })

  it('asks the owner to connect anew where her account reaches another target', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          privateLibrary: true,
          sourceBlock: {
            reason: 'TARGET_OUTSIDE_PROFILE',
            responsible: 'Besitzerin der Bibliothek',
            notice: 'Ziel weicht ab: … Die Besitzerin verbindet ihr Konto neu.',
            action: 'CONNECT_OWN_ACCOUNT',
          },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    const notice = screen.getByTestId('source-lock-notice')
    expect(within(notice).getByRole('link', { name: 'Konto neu verbinden' })).toBeVisible()
  })

  it('opens the source configuration where the managers enter the secret anew', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          sourceBlock: {
            reason: 'NOT_CONNECTED',
            responsible: 'Verwaltende der Bibliothek',
            notice: 'Verbindung getrennt: Für den Zugang sind keine Zugangsdaten hinterlegt.',
            action: 'EDIT_SOURCE',
          },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    const notice = screen.getByTestId('source-lock-notice')
    expect(within(notice).queryByRole('link')).not.toBeInTheDocument()
    await user.click(within(notice).getByRole('button', { name: 'Quelle bearbeiten' }))
    // „Quelle bearbeiten“ opens the row „Verbindung“ with its fields in place
    const row = screen.getByRole('region', { name: 'Verbindung' })
    expect(await within(row).findByLabelText('App-Passwort', { selector: 'input' })).toBeVisible()
  })

  it('offers a reader no action, whatever the block names', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          sourceBlock: {
            reason: 'NOT_CONNECTED',
            responsible: 'Verwaltende der Bibliothek',
            notice: 'Verbindung getrennt.',
            action: 'EDIT_SOURCE',
          },
        }}
        canEditSource={false}
      />,
      { withRouter: true },
    )

    const notice = screen.getByTestId('source-lock-notice')
    expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
    expect(within(notice).queryByRole('link')).not.toBeInTheDocument()
  })

  it.each(['OWNER_DEACTIVATED', 'DORMANT'] as const)(
    'shows the notice of %s without an action',
    (reason) => {
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
    },
  )

  it('names the day the content of a deactivated owner is erased from', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{
          ...nextcloud,
          privateLibrary: true,
          sourceBlock: {
            reason: 'OWNER_DEACTIVATED',
            responsible: 'Systemverwaltung',
            notice: 'Konto deaktiviert: …',
            contentDeletedOn: '2026-11-03',
          },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    expect(screen.getByTestId('source-lock-notice')).toHaveTextContent('Löschung ab dem 03.11.2026')
  })
})

describe('LibrarySourceSection - private Bibliothek: Kontingent und Löschung (#2276, #2165)', () => {
  const nextcloud = {
    name: 'Meine Ablage',
    sourceType: 'NEXTCLOUD',
    sourceUrl: 'https://cloud.intern.example/remote.php/dav/files/avogt',
    privateLibrary: true,
  }

  function quotaRun(id: string) {
    return {
      id,
      status: 'COMPLETED' as const,
      triggeredBy: 'MANUAL' as const,
      runMode: 'FULL' as const,
      documentCount: 12,
      totalDocuments: 40,
      documentsSkipped: 0,
      documentsFailed: 0,
      documentsIndexedTotal: 12,
      message:
        'Indizierung abgeschlossen: 12 verarbeitet, 0 übersprungen, 0 fehlgeschlagen — unvollständig: Speicherkontingent Ihrer privaten Bibliotheken erschöpft',
      failureCategory: 'QUOTA_EXHAUSTED',
      incomplete: true,
      startedAt: '2026-10-04T10:00:00Z',
      completedAt: '2026-10-04T10:05:00Z',
      events: [],
      eventsTruncatedCount: 0,
    }
  }

  it('marks a run of a private library ended at a quota and names both quotas and their remedies', async () => {
    server.use(
      http.get('/api/v1/libraries/:libraryId/indexing/runs', () =>
        HttpResponse.json({ runs: [quotaRun('run-quota')] }),
      ),
    )
    renderWithProviders(
      <LibrarySourceSection libraryId="library-quota" library={nextcloud} canEditSource />,
      { withRouter: true },
    )

    const chip = await screen.findByTestId('run-incomplete-run-quota')
    // the run may have hit the library's own quota first: the chip names neither
    expect(chip).toHaveTextContent(
      'unvollständig: Speicherkontingent erschöpft, Dateien übersprungen',
    )
    expect(chip).not.toHaveTextContent('privaten')
    expect(chip).not.toHaveTextContent('wird fortgesetzt')
    const remedy = screen.getByTestId('run-quota-remedy-run-quota')
    expect(remedy).toHaveTextContent(/Kontingent dieser Bibliothek/)
    expect(remedy).toHaveTextContent(/gemeinsame Kontingent Ihrer privaten Bibliotheken/)
    expect(remedy).toHaveTextContent(/Laufprotokoll/)
    expect(remedy).toHaveTextContent(/eine ganze private Bibliothek löschen/)
    expect(remedy).toHaveTextContent(/legt der Betrieb fest/)
    expect(remedy).toHaveTextContent(/Systemverwaltung/)
  })

  it('names the quota of a shared library without speaking of private libraries', async () => {
    server.use(
      http.get('/api/v1/libraries/:libraryId/indexing/runs', () =>
        HttpResponse.json({ runs: [quotaRun('run-library-quota')] }),
      ),
    )
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-shared-quota"
        library={{ ...nextcloud, privateLibrary: false }}
        canEditSource
      />,
      { withRouter: true },
    )

    const chip = await screen.findByTestId('run-incomplete-run-library-quota')
    expect(chip).toHaveTextContent(
      'unvollständig: Speicherkontingent erschöpft, Dateien übersprungen',
    )
    expect(chip).not.toHaveTextContent('privaten')
    const remedy = screen.getByTestId('run-quota-remedy-run-library-quota')
    expect(remedy).toHaveTextContent(/in der Quelle löschen/)
    expect(remedy).not.toHaveTextContent(/private Bibliothek/)
  })

  it('keeps the budget wording for any other incomplete run', async () => {
    server.use(
      http.get('/api/v1/libraries/:libraryId/indexing/runs', () =>
        HttpResponse.json({
          runs: [{ ...quotaRun('run-budget'), failureCategory: null }],
        }),
      ),
    )
    renderWithProviders(
      <LibrarySourceSection libraryId="library-budget" library={nextcloud} canEditSource />,
      { withRouter: true },
    )

    expect(await screen.findByTestId('run-incomplete-run-budget')).toHaveTextContent(
      'unvollständig, wird fortgesetzt',
    )
    expect(screen.queryByTestId('run-quota-remedy-run-budget')).not.toBeInTheDocument()
  })

  it('offers no change of source, profile or schedule while the library is being erased', async () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-erasing"
        library={{
          ...nextcloud,
          sourceBlock: {
            reason: 'NOT_CONNECTED',
            responsible: 'Besitzerin',
            notice: 'Verbindung getrennt.',
            action: 'CONNECT_OWN_ACCOUNT',
          },
        }}
        canEditSource
        erasing
      />,
      { withRouter: true },
    )

    expect(await screen.findByRole('heading', { name: 'Läufe' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Anbindung' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Zeitplan' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Quellkonfiguration bearbeiten' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Zeitplan bearbeiten' })).not.toBeInTheDocument()
    expect(screen.queryByText('Zugang wechseln')).not.toBeInTheDocument()
    const notice = screen.getByTestId('source-lock-notice')
    expect(within(notice).queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByText(/sehen nur Verwaltende/)).not.toBeInTheDocument()
  })
})

describe('LibrarySourceSection - „Ändern“ einer Zeile', () => {
  const update = vi.fn()
  const sharePointProfile = {
    id: 'profile-sp',
    name: 'Microsoft 365',
    serverUrl: 'https://graph.microsoft.com',
    authMethod: 'CLIENT_CREDENTIALS' as const,
  }
  const sharePoint = {
    name: 'Rathaus',
    sourceType: 'SHAREPOINT',
    sourceUrl: 'https://graph.microsoft.com',
    connectionProfile: sharePointProfile,
    sourceSettings: { libraries: [{ driveId: 'd-1', name: 'Dokumente', folders: [] }] },
  }

  beforeEach(() => {
    update.mockReset().mockResolvedValue(undefined)
    useLibraryStore.setState({ updateExistingLibrary: update })
    server.use(
      http.post('/api/v1/source-types/SHAREPOINT/browse', async ({ request }) => {
        const body = (await request.json()) as { query?: Record<string, unknown> }
        const entries =
          typeof body.query?.site === 'string'
            ? [
                { key: 'drive:d-1', name: 'Dokumente' },
                { key: 'drive:d-2', name: 'Protokolle' },
              ]
            : [{ key: 'site:s-1', name: 'Rathaus' }]
        return HttpResponse.json({ entries, complete: true, message: null })
      }),
    )
  })

  it('opens the selection dialog of a dialog source at once and saves on „Übernehmen“', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection libraryId="library-sp" library={sharePoint} canEditSource />,
      { withRouter: true },
    )

    await user.click(screen.getByRole('button', { name: /^Ändern\s*Umfang$/ }))
    const dialog = await screen.findByRole('dialog', { name: /auswählen/ })
    await user.click(await within(dialog).findByRole('button', { name: '„Rathaus“ öffnen' }))
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Protokolle' }))
    expect(update).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: 'Übernehmen' }))

    // no „Speichern“ in between: „Übernehmen“ is the save of the row
    await waitFor(() =>
      expect(update).toHaveBeenCalledWith(
        'library-sp',
        expect.objectContaining({
          name: 'Rathaus',
          sourceSettings: expect.objectContaining({
            libraries: expect.arrayContaining([
              expect.objectContaining({ driveId: 'd-1' }),
              expect.objectContaining({ driveId: 'd-2' }),
            ]),
          }),
        }),
      ),
    )
    expect(update).toHaveBeenCalledTimes(1)
    expect(await screen.findByText(/^Umfang gespeichert/)).toBeVisible()
  })

  it('saves nothing when the dialog of a dialog source is cancelled, and closes the row', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection libraryId="library-sp" library={sharePoint} canEditSource />,
      { withRouter: true },
    )

    await user.click(screen.getByRole('button', { name: /^Ändern\s*Umfang$/ }))
    const dialog = await screen.findByRole('dialog', { name: /auswählen/ })
    await user.click(await within(dialog).findByRole('button', { name: '„Rathaus“ öffnen' }))
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Protokolle' }))
    await user.click(within(dialog).getByRole('button', { name: 'Abbrechen' }))
    await answerConfirm(user, 'Geänderte Auswahl verwerfen?', 'Verwerfen')

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(update).not.toHaveBeenCalled()
    const row = screen.getByRole('region', { name: 'Umfang' })
    expect(within(row).getByRole('button', { name: /^Ändern\s*Umfang$/ })).toBeVisible()
    expect(row).toHaveTextContent('Dokumente')
    expect(row).not.toHaveTextContent('Protokolle')
  })

  it('names the rights, not an address, when a document library at the fixed Graph address is out of reach', async () => {
    server.use(
      http.post('/api/v1/libraries/source-test', () =>
        HttpResponse.json({
          reachable: false,
          credentialsVerified: true,
          message: 'Die Dokumentbibliothek ist für die Anwendung nicht erreichbar.',
          details: { libraries: [{ driveId: 'd-1', reachable: false }] },
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection libraryId="library-sp" library={sharePoint} canEditSource />,
      { withRouter: true },
    )

    await user.click(screen.getByRole('button', { name: /^Ändern\s*Verbindung$/ }))
    const row = screen.getByRole('region', { name: 'Verbindung' })
    await user.click(within(row).getByRole('button', { name: 'Verbindung testen' }))

    expect(await within(row).findByText('Rechte 1 von 1 nicht lesbar')).toBeVisible()
    expect(within(row).getByText('Anmeldung angenommen')).toBeVisible()
    expect(within(row).queryByText(/^Adresse/)).not.toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: 'Speichern' }))
    expect(await screen.findByText(/Verbindung: Test \(Rechte\)/)).toBeInTheDocument()
  })

  it('names a refused sign-in at the fixed Graph address without an address stage', async () => {
    server.use(
      http.post('/api/v1/libraries/source-test', () =>
        HttpResponse.json({
          reachable: false,
          credentialsVerified: false,
          message: 'Entra hat die Anmeldung der App abgelehnt.',
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection libraryId="library-sp" library={sharePoint} canEditSource />,
      { withRouter: true },
    )

    await user.click(screen.getByRole('button', { name: /^Ändern\s*Verbindung$/ }))
    const row = screen.getByRole('region', { name: 'Verbindung' })
    await user.click(within(row).getByRole('button', { name: 'Verbindung testen' }))

    expect(await within(row).findByText('Anmeldung abgelehnt')).toBeVisible()
    expect(within(row).getByText('Rechte nicht geprüft')).toBeVisible()
    expect(within(row).queryByText(/^Adresse/)).not.toBeInTheDocument()
  })

  it.each([
    [
      'a refusal before the sign-in',
      () =>
        HttpResponse.json({
          reachable: false,
          message: 'Bitte mindestens eine Dokumentbibliothek wählen.',
        }),
    ],
    ['a network error', () => HttpResponse.error()],
  ])('blames no stage at the fixed Graph address for %s', async (_case, answer) => {
    server.use(http.post('/api/v1/libraries/source-test', answer))
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection libraryId="library-sp" library={sharePoint} canEditSource />,
      { withRouter: true },
    )

    await user.click(screen.getByRole('button', { name: /^Ändern\s*Verbindung$/ }))
    const row = screen.getByRole('region', { name: 'Verbindung' })
    await user.click(within(row).getByRole('button', { name: 'Verbindung testen' }))

    expect(await within(row).findByText('Anmeldung nicht geprüft')).toBeVisible()
    expect(within(row).getByText('Rechte nicht geprüft')).toBeVisible()
    expect(within(row).queryByText(/^Adresse|nicht möglich/)).not.toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: 'Speichern' }))
    expect(await screen.findByText('Verbindung: Test')).toBeInTheDocument()
    expect(update).not.toHaveBeenCalled()
  })

  it('opens an inline row in place: „Abbrechen“ discards it, „Speichern“ saves it', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-fs"
        library={{
          name: 'Satzungen',
          sourceType: 'FILESYSTEM',
          sourcePath: '/data/satzungen',
          schedule: { frequency: 'DISABLED' },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    const row = () => screen.getByRole('region', { name: 'Zeitplan' })
    await user.click(screen.getByRole('button', { name: /^Ändern\s*Zeitplan$/ }))
    await user.click(within(row()).getByRole('combobox', { name: /^häufigkeit$/i }))
    await user.click(await screen.findByRole('option', { name: 'Stündlich' }))
    await user.click(within(row()).getByRole('button', { name: 'Abbrechen' }))
    await answerConfirm(user, 'Änderungen an „Zeitplan“ verwerfen?', 'Verwerfen')

    expect(update).not.toHaveBeenCalled()
    expect(row()).toHaveTextContent('aus, nur „Jetzt indizieren“')
    expect(within(row()).queryByRole('combobox')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /^Ändern\s*Zeitplan$/ }))
    // the row starts again from what is stored
    expect(within(row()).getByRole('combobox', { name: /^häufigkeit$/i })).toHaveTextContent('Aus')
    await user.click(within(row()).getByRole('combobox', { name: /^häufigkeit$/i }))
    await user.click(await screen.findByRole('option', { name: 'Stündlich' }))
    await user.click(within(row()).getByRole('button', { name: 'Speichern' }))

    await waitFor(() =>
      expect(update).toHaveBeenCalledWith('library-fs', {
        name: 'Satzungen',
        description: undefined,
        schedule: { frequency: 'HOURLY', hour: null, minute: null, weekday: undefined },
      }),
    )
    expect(await screen.findByText('Zeitplan gespeichert.')).toBeVisible()
  })

  it('names a failed save without HTTP codes and keeps the row open', async () => {
    update
      .mockRejectedValueOnce(new Error('HTTP 502: Request failed with status code 502'))
      .mockRejectedValueOnce(new Error('Der Zeitplan ist ungültig (HTTP 400).'))
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-fs"
        library={{
          name: 'Satzungen',
          sourceType: 'FILESYSTEM',
          sourcePath: '/data/satzungen',
          schedule: { frequency: 'DISABLED' },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    const row = () => screen.getByRole('region', { name: 'Zeitplan' })
    await user.click(screen.getByRole('button', { name: /^Ändern\s*Zeitplan$/ }))
    await user.click(within(row()).getByRole('combobox', { name: /^häufigkeit$/i }))
    await user.click(await screen.findByRole('option', { name: 'Stündlich' }))
    await user.click(within(row()).getByRole('button', { name: 'Speichern' }))
    expect(await within(row()).findByText('Die Änderung ließ sich nicht speichern.')).toBeVisible()
    await user.click(within(row()).getByRole('button', { name: 'Speichern' }))
    expect(await within(row()).findByText('Der Zeitplan ist ungültig.')).toBeVisible()
    expect(row()).not.toHaveTextContent(/HTTP|502|400/)
    expect(within(row()).getByRole('combobox', { name: /^häufigkeit$/i })).toBeVisible()
  })

  it('keeps a choice of an inline scope as a draft until „Speichern“', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-wiki"
        library={{
          name: 'Wiki',
          sourceType: 'CONFLUENCE',
          sourceUrl: 'https://wiki.rheinfurt.example',
          sourceCredentialsSet: true,
          sourceSettings: { edition: 'DATA_CENTER', spaces: [{ key: 'BAU', name: 'Bauamt' }] },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    await user.click(screen.getByRole('button', { name: /^Ändern\s*Umfang$/ }))
    const row = screen.getByRole('region', { name: 'Umfang' })
    const spaces = await within(row).findAllByRole('checkbox')
    const other = spaces.find((box) => !(box as HTMLInputElement).checked)!
    await user.click(other)
    expect(update).not.toHaveBeenCalled()

    await user.click(within(row).getByRole('button', { name: 'Speichern' }))
    await waitFor(() =>
      expect(update).toHaveBeenCalledWith(
        'library-wiki',
        expect.objectContaining({
          sourceSettings: expect.objectContaining({
            spaces: expect.arrayContaining([expect.objectContaining({ key: 'BAU' })]),
          }),
        }),
      ),
    )
    const sent = update.mock.calls[0][1] as { sourceSettings: { spaces: unknown[] } }
    expect(sent.sourceSettings.spaces).toHaveLength(2)
  })
})

describe('LibrarySourceSection - Zugangsdaten beim Bearbeiten (#3140, #1868)', () => {
  const update = vi.fn()
  let probes: SourceConnectionTestRequest[] = []
  const smb = {
    name: 'Ablage',
    sourceType: 'SMB',
    sourceUrl: 'smb://dateiserver/Daten',
    sourceCredentialsSet: true,
    sourceSettings: { folders: ['/'] },
  }

  beforeEach(() => {
    update.mockReset().mockResolvedValue(undefined)
    useLibraryStore.setState({ updateExistingLibrary: update })
    probes = []
    server.use(
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

  async function editAddress(user: ReturnType<typeof userEvent.setup>, address: string) {
    await openConnection(user)
    const row = screen.getByRole('region', { name: 'Verbindung' })
    const field = within(row).getByRole('textbox', { name: 'Adresse' })
    await user.clear(field)
    await user.type(field, address)
    return row
  }

  function renderSmb() {
    renderWithProviders(
      <LibrarySourceSection libraryId="library-smb" library={smb} canEditSource />,
      { withRouter: true },
    )
  }

  it('keeps the stored credentials while the fields stay empty and the share stays', async () => {
    const user = userEvent.setup()
    renderSmb()

    const row = await editAddress(user, 'smb://Dateiserver/Daten')
    expect(within(row).getByRole('button', { name: 'Zugangsdaten ersetzen' })).toBeVisible()
    await user.click(within(row).getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][1].sourceCredentials).toBeUndefined()
  })

  it('asks for new credentials once the share changes on the same server', async () => {
    const user = userEvent.setup()
    renderSmb()

    const row = await editAddress(user, 'smb://dateiserver/Personal')
    await user.click(within(row).getByRole('button', { name: 'Speichern' }))

    expect(await within(row).findByText('Passwort fehlt')).toBeVisible()
    expect(within(row).queryByRole('button', { name: 'Zugangsdaten ersetzen' })).toBeNull()
    expect(update).not.toHaveBeenCalled()
  })

  it('asks for new credentials once the server changes', async () => {
    const user = userEvent.setup()
    renderSmb()

    const row = await editAddress(user, 'smb://anderer/Daten')
    await user.click(within(row).getByRole('button', { name: 'Speichern' }))

    expect(await within(row).findByText('Passwort fehlt')).toBeVisible()
    expect(update).not.toHaveBeenCalled()
  })

  it('replaces the stored credentials with newly entered ones', async () => {
    const user = userEvent.setup()
    renderSmb()

    await openConnection(user)
    const row = screen.getByRole('region', { name: 'Verbindung' })
    await user.click(within(row).getByRole('button', { name: 'Zugangsdaten ersetzen' }))
    await user.type(within(row).getByRole('textbox', { name: 'Benutzername' }), 'svc-neu')
    await user.type(within(row).getByLabelText('Passwort'), 'geheim')
    await user.click(within(row).getByRole('button', { name: 'Speichern' }))

    await waitFor(() =>
      expect(update).toHaveBeenCalledWith(
        'library-smb',
        expect.objectContaining({ sourceCredentials: 'svc-neu:geheim' }),
      ),
    )
  })

  it('tests the connection of the stored library by its id (#1868)', async () => {
    const user = userEvent.setup()
    renderSmb()

    await openConnection(user)
    const row = screen.getByRole('region', { name: 'Verbindung' })
    await user.click(within(row).getByRole('button', { name: 'Verbindung testen' }))

    await waitFor(() => expect(probes).toHaveLength(1))
    expect(probes[0]).toMatchObject({ libraryId: 'library-smb', sourceType: 'SMB' })
    expect(probes[0].sourceCredentials).toBeUndefined()
  })
})

describe('LibrarySourceSection - SharePoint-Kennungen (#3140)', () => {
  const update = vi.fn()

  beforeEach(() => {
    update.mockReset().mockResolvedValue(undefined)
    useLibraryStore.setState({ updateExistingLibrary: update })
    server.use(
      http.post('/api/v1/source-types/SHAREPOINT/browse', async ({ request }) => {
        const body = (await request.json()) as { query?: Record<string, unknown> }
        const entries =
          typeof body.query?.site === 'string'
            ? [{ key: 'drive:d-2', name: 'Protokolle' }]
            : [{ key: 'site:s-1', name: 'Rathaus' }]
        return HttpResponse.json({ entries, complete: true, message: null })
      }),
    )
  })

  it('rejects a document library whose id the connector does not accept, at the field', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-sp"
        library={{
          name: 'Rathaus',
          sourceType: 'SHAREPOINT',
          sourceUrl: 'https://graph.microsoft.com',
          connectionProfile: {
            id: 'profile-sp',
            name: 'Microsoft 365',
            serverUrl: 'https://graph.microsoft.com',
            authMethod: 'CLIENT_CREDENTIALS',
          },
          sourceSettings: { libraries: [{ driveId: 'd 1/..', name: 'Dokumente', folders: [] }] },
        }}
        canEditSource
      />,
      { withRouter: true },
    )

    await user.click(screen.getByRole('button', { name: /^Ändern\s*Umfang$/ }))
    const dialog = await screen.findByRole('dialog', { name: /auswählen/ })
    await user.click(await within(dialog).findByRole('button', { name: '„Rathaus“ öffnen' }))
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Protokolle' }))
    await user.click(within(dialog).getByRole('button', { name: 'Übernehmen' }))

    const row = screen.getByRole('region', { name: 'Umfang' })
    expect(await within(row).findByText(/keine gültige Kennung/)).toBeVisible()
    expect(update).not.toHaveBeenCalled()
  })
})
