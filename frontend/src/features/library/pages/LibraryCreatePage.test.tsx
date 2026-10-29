import { describe, expect, it, beforeEach, vi } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { answerConfirm, renderWithProviders } from '../../../test/test-utils'
import LibraryCreatePage from './LibraryCreatePage'
import { useLibraryStore } from '../stores/libraryStore'
import { useIndexingStore } from '../stores/indexingStore'
import { capabilityMissingMessage } from '@/shared/utils/labels'
import { mockSourceTypes } from '../mocks/libraryFixtures'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return { ...actual, useNavigate: () => mockNavigate }
})

const {
  mockGetMyGroups,
  mockGetMyCapabilities,
  mockTestLibrarySource,
  mockListConfluenceSpaces,
  mockListS3Buckets,
  mockBrowseSource,
  mockListSourceTypes,
  mockGetUserSummaries,
  mockUpsertAssetGrant,
  mockSearchSelectableGroups,
  mockListConnectionProfileOptions,
} = vi.hoisted(() => ({
  mockListConnectionProfileOptions: vi.fn(),
  mockGetMyGroups: vi.fn().mockResolvedValue([]),
  mockGetMyCapabilities: vi.fn(),
  mockTestLibrarySource: vi.fn(),
  mockListConfluenceSpaces: vi.fn(),
  mockListS3Buckets: vi.fn(),
  mockBrowseSource: vi.fn(),
  mockListSourceTypes: vi.fn(),
  mockGetUserSummaries: vi.fn().mockResolvedValue([]),
  mockUpsertAssetGrant: vi.fn(),
  mockSearchSelectableGroups: vi.fn().mockResolvedValue([]),
}))

vi.mock('@/features/principal/api/groupLookupApi', async () => {
  const actual = await vi.importActual<typeof import('@/features/principal/api/groupLookupApi')>(
    '@/features/principal/api/groupLookupApi',
  )
  return {
    ...actual,
    getMyGroups: mockGetMyGroups,
    searchSelectableGroups: mockSearchSelectableGroups,
  }
})

vi.mock('@/shared/session/capabilityApi', async () => {
  const actual = await vi.importActual<typeof import('@/shared/session/capabilityApi')>(
    '@/shared/session/capabilityApi',
  )
  return {
    ...actual,
    getMyCapabilities: mockGetMyCapabilities,
  }
})

vi.mock('@/features/principal/api/userApi', async () => {
  const actual = await vi.importActual<typeof import('@/features/principal/api/userApi')>(
    '@/features/principal/api/userApi',
  )
  return {
    ...actual,
    getUserSummaries: mockGetUserSummaries,
  }
})

vi.mock('../../asset/api/assetApi', async () => {
  const actual = await vi.importActual<typeof import('../../asset/api/assetApi')>(
    '../../asset/api/assetApi',
  )
  return {
    ...actual,
    upsertAssetGrant: mockUpsertAssetGrant,
  }
})

vi.mock('../../connection/api/connectionProfileApi', async () => {
  const actual = await vi.importActual<typeof import('../../connection/api/connectionProfileApi')>(
    '../../connection/api/connectionProfileApi',
  )
  return { ...actual, listConnectionProfileOptions: mockListConnectionProfileOptions }
})

vi.mock('../api/libraryApi', async () => {
  const actual = await vi.importActual<typeof import('../api/libraryApi')>('../api/libraryApi')
  return {
    ...actual,
    testLibrarySource: mockTestLibrarySource,
    browseSource: mockBrowseSource,
  }
})

vi.mock('../../connection/components/sourceTypeApi', () => ({
  listSourceTypes: mockListSourceTypes,
}))

const mockCreateNewLibrary = vi.fn().mockResolvedValue('lib-neu')
const mockTriggerIndexing = vi.fn().mockResolvedValue(undefined)

type User = ReturnType<typeof userEvent.setup>

/** Renders the wizard and waits for its tiles, which follow GET /source-types (ADR-0038). */
async function renderPage() {
  const rendered = renderWithProviders(<LibraryCreatePage />, { withRouter: true })
  await screen.findByRole('radiogroup', { name: 'Quellart wählen' })
  return rendered
}

function next(user: User) {
  return user.click(screen.getByRole('button', { name: 'Weiter' }))
}

/** Step 1 „Quellart": choose a tile and go on - then „Quelle einrichten" (or „Name") follows. */
async function chooseType(user: User, label: RegExp) {
  await user.click(screen.getByRole('radio', { name: label }))
  await next(user)
}

/** Step „Name & Beschreibung": overwrite the prefilled name and go on to „Freigaben". */
async function nameItAndContinue(user: User, name: string) {
  const field = screen.getByLabelText(/^Name/)
  await user.clear(field)
  await user.type(field, name)
  await next(user)
}

/** A summary row of „Quelle einrichten“ or „Prüfen“ by its title. */
function rowOf(title: string) {
  return screen.getByRole('region', { name: title })
}

/** „Ändern“ at a summary row; its accessible name carries the row's title. */
function changeRow(user: User, title: string) {
  return user.click(screen.getByRole('button', { name: new RegExp(`^Ändern\\s*${title}$`) }))
}

/** Leaves the preselected scope „Alle …“ for a selection of its own. */
async function onlySelected(user: User, plural: string) {
  await user.click(await screen.findByRole('radio', { name: `Nur ausgewählte ${plural}` }))
}

/** Opens the selection dialog by its link and enters `texts` by hand, then „Übernehmen“. */
async function chooseByHand(user: User, link: string, label: string, texts: string[]) {
  await user.click(await screen.findByRole('button', { name: link }))
  const dialog = await screen.findByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: 'Von Hand eintragen …' }))
  for (const text of texts) {
    await user.type(within(dialog).getByLabelText(label), text)
    await user.click(within(dialog).getByRole('button', { name: 'Hinzufügen' }))
  }
  await user.click(within(dialog).getByRole('button', { name: 'Übernehmen' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
}

/** From the last step before „Prüfen“ (or from „Prüfen“ itself): create the library there. */
async function create(user: User) {
  if (!screen.queryByRole('button', { name: 'Bibliothek anlegen' })) await next(user)
  await user.click(screen.getByRole('button', { name: 'Bibliothek anlegen' }))
}

describe('LibraryCreatePage (#596, #1942)', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    mockCreateNewLibrary.mockClear()
    mockTriggerIndexing.mockClear()
    mockGetMyGroups.mockResolvedValue([])
    mockGetMyCapabilities.mockResolvedValue([
      'CREATE_SPACE',
      'CREATE_LIBRARY',
      'CREATE_CONNECTOR_LIBRARY',
    ])
    useLibraryStore.setState({ createNewLibrary: mockCreateNewLibrary })
    mockListSourceTypes.mockResolvedValue(mockSourceTypes)
    mockListConnectionProfileOptions.mockReset()
    mockListConnectionProfileOptions.mockResolvedValue([])
    mockUpsertAssetGrant.mockClear()
    // „Weiter“ out of „Quelle einrichten“ tests the connection unless it already passed
    mockTestLibrarySource.mockResolvedValue({ reachable: true, message: 'Quelle erreichbar.' })
    // the per-connector listings of the forms, answered in the shape of the one browse endpoint
    mockBrowseSource.mockImplementation(async (sourceType: string, request: unknown) => {
      if (sourceType === 'CONFLUENCE') {
        const { spaces } = await mockListConfluenceSpaces(request)
        return { complete: true, entries: spaces }
      }
      const { listingPermitted, buckets, message } = await mockListS3Buckets(request)
      return {
        complete: listingPermitted,
        entries: buckets.map((key: string) => ({ key })),
        message,
      }
    })
    useIndexingStore.setState({ triggerIndexing: mockTriggerIndexing })
  })

  // ADR-0038: the tiles follow GET /source-types; a type without a form in this client is shown
  // under its own name and locked, with the reason as text.
  it('shows a source type the client has no form for as a locked tile with its reason', async () => {
    mockListSourceTypes.mockResolvedValue([
      ...mockSourceTypes,
      {
        type: 'PROBE',
        displayName: 'Testquelle',
        indexingRun: false,
        uploads: false,
        pushIntake: false,
        browsable: false,
        profileSupport: 'FORBIDDEN',
        signIns: [],
        profileDefaults: [],
        serverAddress: { schemes: ['https', 'http'] },
      },
    ])
    await renderPage()

    const tile = screen.getByRole('radio', { name: /Testquelle/ })
    expect(tile).toBeDisabled()
    expect(tile).toHaveAccessibleDescription(
      'Für diese Quellart gibt es in dieser Oberfläche keine Eingabemaske.',
    )
    expect(screen.getByRole('radio', { name: /Upload/ })).toBeEnabled()
  })

  it('names a failed source-type list in German and does not go on with the upload library', async () => {
    mockListSourceTypes.mockRejectedValue(
      new Error('HTTP 502: Request failed with status code 502'),
    )
    renderWithProviders(<LibraryCreatePage />, { withRouter: true })

    expect(
      await screen.findByText(/Die Quellarten konnten nicht geladen werden/),
    ).toBeInTheDocument()
    expect(screen.queryByText(/HTTP 502/)).not.toBeInTheDocument()
    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument()
    // „Weiter“ stays enabled and says why it cannot go on, at the step it stays on
    await next(userEvent.setup())
    expect(
      screen.getByRole('heading', { level: 2, name: 'Woher kommt das Wissen?' }),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText(/^Name/)).not.toBeInTheDocument()
    expect(screen.queryByText(/HTTP 502/)).not.toBeInTheDocument()
  })

  it('loads the source types again on „Erneut versuchen"', async () => {
    const user = userEvent.setup()
    mockListSourceTypes.mockRejectedValueOnce(new Error('HTTP 502'))
    renderWithProviders(<LibraryCreatePage />, { withRouter: true })

    await user.click(await screen.findByRole('button', { name: 'Erneut versuchen' }))

    expect(await screen.findByRole('radiogroup')).toBeInTheDocument()
    expect(
      screen.queryByText(/Die Quellarten konnten nicht geladen werden/),
    ).not.toBeInTheDocument()
  })

  // The steps are named after what one does in them; an upload library has no source and so no
  // step „Quelle einrichten".
  it('names the steps and drops "Quelle einrichten" for an upload library', async () => {
    const user = userEvent.setup()
    await renderPage()

    expect(screen.getByText('1 · Quellart')).toBeInTheDocument()
    expect(screen.getByText('2 · Name')).toBeInTheDocument()
    expect(screen.getByText('3 · Freigaben')).toBeInTheDocument()
    expect(screen.getByText('4 · Prüfen')).toBeInTheDocument()
    expect(screen.queryByText(/· Quelle$/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /Confluence/ }))
    expect(screen.getByText('2 · Quelle')).toBeInTheDocument()
    expect(screen.getByText('3 · Name')).toBeInTheDocument()
    expect(screen.getByText('4 · Freigaben')).toBeInTheDocument()
    expect(screen.getByText('5 · Prüfen')).toBeInTheDocument()
  })

  it('blocks Weiter without a name and says so', async () => {
    const user = userEvent.setup()
    await renderPage()

    await next(user)
    await next(user)
    expect(await screen.findByText('Name fehlt')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Name/)).toBeInTheDocument()
  })

  it('carries the focus to the heading of the step just entered', async () => {
    const user = userEvent.setup()
    await renderPage()

    await next(user)

    const heading = screen.getByRole('heading', { level: 2, name: 'Name & Beschreibung' })
    expect(heading).toHaveFocus()
  })

  describe('Anlegerechte (#1813, ADR-0036 Entscheidung 5)', () => {
    it('hides the tile of a kind the caller may not create', async () => {
      mockGetMyCapabilities.mockResolvedValue(['CREATE_CONNECTOR_LIBRARY'])
      await renderPage()

      await waitFor(() =>
        expect(screen.queryByRole('radio', { name: /Upload/ })).not.toBeInTheDocument(),
      )
      expect(screen.getByRole('radio', { name: /Confluence/ })).toBeEnabled()
      expect(screen.queryByText(capabilityMissingMessage('CREATE_LIBRARY'))).not.toBeInTheDocument()
    })

    it('shows an empty step naming the system administration when nothing may be created', async () => {
      mockGetMyCapabilities.mockResolvedValue([])
      renderWithProviders(<LibraryCreatePage />, { withRouter: true })

      await screen.findByText(/Sie dürfen derzeit keine Bibliothek anlegen/)
      expect(document.getElementById('library-create-capability-hint')).toHaveTextContent(
        'Wenden Sie sich an die Systemverwaltung',
      )
      expect(screen.queryByRole('radio')).not.toBeInTheDocument()
      // „Weiter“ is never disabled; with nothing creatable it does not leave the first step
      await next(userEvent.setup())
      expect(screen.getByText('1 · Quellart').closest('li')).toHaveAttribute('aria-current', 'step')
      expect(screen.queryByLabelText(/^Name/)).not.toBeInTheDocument()
    })

    // The connector release applies per type: a type without a usable way - not released, locked
    // or without a usable profile - does not appear in the wizard at all.
    it('hides the tile of a source type the person has no usable way for', async () => {
      const notice =
        'Ihnen fehlt das Anlegerecht „Konnektorbibliotheken anlegen“ für die Quellart „RSS-Feed“.'
      mockListSourceTypes.mockResolvedValue(
        mockSourceTypes.map((descriptor) =>
          descriptor.type === 'RSS_FEED'
            ? {
                ...descriptor,
                creatable: false,
                creatableWithOwnAddress: false,
                creationNotice: notice,
              }
            : descriptor,
        ),
      )
      await renderPage()

      expect(await screen.findByRole('radio', { name: /Confluence/ })).toBeEnabled()
      expect(screen.queryByRole('radio', { name: /RSS-Feed/ })).not.toBeInTheDocument()
      expect(screen.queryByText(notice)).not.toBeInTheDocument()
    })

    it('shows only the upload tile without the connector right', async () => {
      mockGetMyCapabilities.mockResolvedValue(['CREATE_LIBRARY'])
      await renderPage()

      await waitFor(() =>
        expect(screen.queryByRole('radio', { name: /Dateisystem/ })).not.toBeInTheDocument(),
      )
      expect(screen.getByRole('radio', { name: /Upload/ })).toBeChecked()
    })

    // The creation rights arrive only after the first render: what is then disabled must not
    // stay selected - otherwise „Weiter" would lead into a path that can never create.
    it('moves the selection off a tile that turns out to be hidden', async () => {
      mockGetMyCapabilities.mockResolvedValue(['CREATE_CONNECTOR_LIBRARY'])
      await renderPage()

      // The first permitted tile moves up; the stepper thus shows „Quelle einrichten" again.
      await waitFor(() =>
        expect(screen.getByRole('radio', { name: /Dateisystem/ })).toHaveAttribute(
          'aria-checked',
          'true',
        ),
      )
      expect(screen.queryByRole('radio', { name: /Upload/ })).not.toBeInTheDocument()
      expect(screen.getByText('2 · Quelle')).toBeInTheDocument()
      // no hint above the wizard: it would concern the chosen type, and that one is allowed now
      expect(document.getElementById('library-create-capability-hint')).toBeNull()
    })
  })

  // WAI-ARIA „radio group": one stop in the tab order, the arrow keys select within.
  it('moves through the tiles with the arrow keys', async () => {
    mockGetMyCapabilities.mockResolvedValue(['CREATE_CONNECTOR_LIBRARY'])
    const user = userEvent.setup()
    await renderPage()

    await waitFor(() =>
      expect(screen.queryByRole('radio', { name: /Upload/ })).not.toBeInTheDocument(),
    )
    const filesystem = screen.getByRole('radio', { name: /Dateisystem/ })
    expect(filesystem).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('radio', { name: /Confluence/ })).toHaveAttribute('tabindex', '-1')

    filesystem.focus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('radio', { name: /Webverzeichnis/ })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    expect(screen.getByRole('radio', { name: /Webverzeichnis/ })).toHaveFocus()

    // backwards past the start lands at the end
    await user.keyboard('{ArrowLeft}{ArrowLeft}')
    expect(screen.getByRole('radio', { name: /Dateifreigabe/ })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    await user.keyboard('{Home}')
    expect(screen.getByRole('radio', { name: /Dateisystem/ })).toHaveAttribute(
      'aria-checked',
      'true',
    )
  }, 15000)

  it('notes a person with the shared subject picker and grants on the asset after creation', async () => {
    mockGetUserSummaries.mockResolvedValue([
      { id: 'user-alice', email: 'alice@example.com', displayName: 'Alice' },
    ])
    mockUpsertAssetGrant.mockResolvedValue({})
    const user = userEvent.setup()
    await renderPage()

    await next(user)
    await nameItAndContinue(user, 'Rechtsquellen Soziales')
    await user.type(screen.getByRole('combobox', { name: 'Person oder Gruppe suchen' }), 'al')
    await user.click(await screen.findByRole('option', { name: /Alice/ }))
    await user.click(screen.getByRole('button', { name: 'Vormerken' }))
    await create(user)

    await waitFor(() =>
      expect(mockUpsertAssetGrant).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', 'lib-neu', {
        subjectType: 'USER',
        subjectId: 'user-alice',
        role: 'VIEWER',
      }),
    )
  }, 15000)

  // The library exists once the POST succeeded: a refused grant leads to its detail page instead
  // of re-enabling "Bibliothek anlegen", which would create a second library.
  it('goes to the new library when a noted grant is refused, and names the grant', async () => {
    mockGetUserSummaries.mockResolvedValue([
      { id: 'user-alice', email: 'alice@example.com', displayName: 'Alice' },
    ])
    mockUpsertAssetGrant.mockRejectedValueOnce(new Error('abgelehnt'))
    const user = userEvent.setup()
    await renderPage()

    await next(user)
    await nameItAndContinue(user, 'Rechtsquellen Soziales')
    await user.type(screen.getByRole('combobox', { name: 'Person oder Gruppe suchen' }), 'al')
    await user.click(await screen.findByRole('option', { name: /Alice/ }))
    await user.click(screen.getByRole('button', { name: 'Vormerken' }))
    await create(user)

    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu?justCreated=1'),
    )
    expect(mockCreateNewLibrary).toHaveBeenCalledTimes(1)
    expect(await screen.findByText(/nicht gespeichert werden: Alice/)).toBeInTheDocument()
  }, 15000)

  /** ADR-0036, Entscheidung 2: the same confirmation question as in „Berechtigungen". */
  it('asks before noting a group of an external provider and notes nothing on cancel', async () => {
    mockSearchSelectableGroups.mockResolvedValue([
      {
        id: 'group-partner',
        name: 'Referat 50',
        origin: 'PROVIDER',
        provider: {
          id: 'oidc-provider-partner',
          displayName: 'Verzeichnis Partner',
          external: true,
          enabled: true,
          groupMechanism: 'TOKEN',
        },
        sourcePath: null,
        activeMemberCount: 23,
        smallGroup: false,
        emptyGroup: false,
        protectedGroup: false,
        selectable: true,
        dissolved: false,
        providerDisabled: false,
        unmaintained: false,
      },
    ])
    const user = userEvent.setup()
    await renderPage()

    await next(user)
    await nameItAndContinue(user, 'Rechtsquellen Soziales')
    await user.type(await screen.findByLabelText('Person oder Gruppe suchen'), 'Referat')
    await user.click(await screen.findByRole('option', { name: /Referat 50/ }))
    await user.click(screen.getByRole('button', { name: 'Vormerken' }))

    const question = 'Sie geben für eine Gruppe eines externen Anbieters frei — fortfahren?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /Verzeichnis Partner/,
    )
    await answerConfirm(user, question, 'Abbrechen')

    await create(user)
    await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
    expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
  }, 20000)

  describe('Zeitplan und Sofortstart im Assistenten (#1942)', () => {
    it('sets the schedule together with the library, for a connector type that never had one here', async () => {
      const user = userEvent.setup()
      await renderPage()

      await chooseType(user, /Dateisystem/)
      await user.type(screen.getByLabelText(/^Verzeichnis/), '/data/dokumente')
      // nightly is preset; the row „Zeitplan“ opens on „Ändern“
      expect(within(rowOf('Zeitplan')).getByText(/täglich 02:00/)).toBeInTheDocument()
      await changeRow(user, 'Zeitplan')
      await user.click(screen.getByRole('combobox', { name: 'Häufigkeit' }))
      await user.click(await screen.findByRole('option', { name: 'Wöchentlich' }))
      await next(user)
      await nameItAndContinue(user, 'Dienstanweisungen')
      await create(user)

      await waitFor(() =>
        expect(mockCreateNewLibrary).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceType: 'FILESYSTEM',
            schedule: { frequency: 'WEEKLY', hour: 2, minute: 0, weekday: 'MONDAY' },
          }),
        ),
      )
      // Immediate start applies to every connector type, not only Confluence and S3.
      expect(mockTriggerIndexing).toHaveBeenCalledWith('lib-neu', 'FILESYSTEM')
    }, 25000)

    it('sends no schedule at all for an upload library', async () => {
      const user = userEvent.setup()
      await renderPage()

      await next(user)
      await nameItAndContinue(user, 'Handakte')
      await create(user)

      await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
      expect(mockCreateNewLibrary.mock.calls[0][0]).not.toHaveProperty('schedule')
      expect(mockTriggerIndexing).not.toHaveBeenCalled()
    }, 15000)
  })

  describe('S3 origin (#1377, ADR-0027)', () => {
    beforeEach(() => {
      mockTestLibrarySource.mockReset()
      mockListS3Buckets.mockReset()
    })

    async function openS3Step() {
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /S3-Objektspeicher/)
      return user
    }

    it('offers the S3 tile and names every missing field at once on Weiter', async () => {
      const user = await openS3Step()

      await next(user)
      // „Alle Buckets“ is preselected: the scope asks for nothing yet
      const summary = screen.getByRole('alert', { name: 'Fehler: 3 Angaben prüfen' })
      expect(
        within(summary)
          .getAllByRole('button')
          .map((entry) => entry.textContent),
      ).toEqual(['Verbindung: Endpoint', 'Verbindung: Access Key', 'Verbindung: Secret Key'])
      expect(within(rowOf('Umfang')).getByText('Alle Buckets')).toBeInTheDocument()
      expect(screen.getByText('Endpoint fehlt')).toBeInTheDocument()
      expect(screen.getByText('Access Key fehlt')).toBeInTheDocument()
      expect(screen.getByText('Secret Key fehlt')).toBeInTheDocument()
      await user.type(screen.getByLabelText('Endpoint'), 'https://minio.intern.example:9000')
      // an issue disappears as soon as its field is valid
      expect(screen.queryByText('Endpoint fehlt')).not.toBeInTheDocument()
      await next(user)
      expect(screen.getByText('Access Key fehlt')).toBeInTheDocument()
      expect(screen.queryByLabelText(/^Name/)).not.toBeInTheDocument()
      expect(mockTestLibrarySource).not.toHaveBeenCalled()
    }, 15000)

    it('creates a MinIO library with the template values and sends its settings', async () => {
      mockTestLibrarySource.mockResolvedValue({ reachable: true, message: 'Erreichbar.' })
      mockListS3Buckets.mockResolvedValue({
        listingPermitted: true,
        buckets: ['protokolle', 'satzungen'],
      })
      const user = await openS3Step()
      await user.type(screen.getByLabelText('Endpoint'), 'https://minio.intern.example:9000')
      await user.type(screen.getByLabelText('Access Key'), 'AKIAEXAMPLE')
      await user.type(screen.getByLabelText('Secret Key'), 'geheim')
      await changeRow(user, 'Umfang')
      await onlySelected(user, 'Buckets und Präfixe')
      await chooseByHand(user, 'Buckets und Präfixe auswählen …', 'Bucket/Präfix', [
        'protokolle/2025/protokolle',
        'satzungen',
      ])
      await next(user)
      // The name is prefilled from the source - here the first bucket.
      expect(screen.getByLabelText(/^Name/)).toHaveValue('protokolle')
      await nameItAndContinue(user, 'Protokolle')
      await create(user)

      await waitFor(() =>
        expect(mockCreateNewLibrary).toHaveBeenCalledWith(
          expect.objectContaining({
            name: 'Protokolle',
            sourceType: 'S3',
            sourceUrl: 'https://minio.intern.example:9000',
            sourceCredentials: 'AKIAEXAMPLE:geheim',
            sourceSettings: {
              region: 'us-east-1',
              pathStyle: true,
              scopes: [
                { bucket: 'protokolle', prefix: '2025/protokolle/' },
                { bucket: 'satzungen', prefix: null },
              ],
              includePatterns: [],
              excludePatterns: [],
            },
          }),
        ),
      )
      expect(mockCreateNewLibrary.mock.calls[0][0].sourceSettings).not.toHaveProperty('spaces')
      // the "Erste Indizierung sofort ..." switch defaults to on: the full sync starts right after
      // creation, before the navigation to the detail page
      expect(mockTriggerIndexing).toHaveBeenCalledWith('lib-neu', 'S3')
      expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu?justCreated=1')
    }, 30000)

    it('creates an AWS library from the template with a derived virtual-host endpoint', async () => {
      mockTestLibrarySource.mockResolvedValue({ reachable: true, message: 'Erreichbar.' })
      mockListS3Buckets.mockResolvedValue({
        listingPermitted: true,
        buckets: ['verwaltung-dokumente', 'archiv'],
      })
      const user = await openS3Step()
      await user.click(screen.getByRole('combobox', { name: 'Anbieter' }))
      await user.click(await screen.findByRole('option', { name: 'AWS S3' }))
      expect(screen.getByLabelText('Endpoint')).toHaveValue('https://s3.eu-central-1.amazonaws.com')
      await user.type(screen.getByLabelText('Access Key'), 'AKIAEXAMPLE')
      await user.type(screen.getByLabelText('Secret Key'), 'geheim')
      await changeRow(user, 'Umfang')
      await onlySelected(user, 'Buckets und Präfixe')
      // up to seven buckets stand as checkboxes in the row
      await user.click(await screen.findByRole('checkbox', { name: 'verwaltung-dokumente' }))
      await changeRow(user, 'Zeitplan')
      await user.click(
        screen.getByRole('switch', { name: 'Erste Indizierung sofort nach dem Anlegen' }),
      )
      await next(user)
      await nameItAndContinue(user, 'Verwaltungsdokumente')
      await create(user)

      await waitFor(() =>
        expect(mockCreateNewLibrary).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceType: 'S3',
            sourceUrl: 'https://s3.eu-central-1.amazonaws.com',
            sourceSettings: expect.objectContaining({
              region: 'eu-central-1',
              pathStyle: false,
              scopes: [{ bucket: 'verwaltung-dokumente', prefix: null }],
            }),
          }),
        ),
      )
      await waitFor(() =>
        expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu?justCreated=1'),
      )
      // switched off: no first run, the detail page's "Jetzt indizieren" or the schedule starts it
      expect(mockTriggerIndexing).not.toHaveBeenCalled()
    }, 25000)

    it('creates „Alle Buckets“ where the key may list them', async () => {
      mockTestLibrarySource.mockResolvedValue({ reachable: true, message: 'Erreichbar.' })
      mockListS3Buckets.mockResolvedValue({ listingPermitted: true, buckets: ['akten'] })
      const user = await openS3Step()
      await user.type(screen.getByLabelText('Endpoint'), 'https://minio.intern.example:9000')
      await user.type(screen.getByLabelText('Access Key'), 'AKIAEXAMPLE')
      await user.type(screen.getByLabelText('Secret Key'), 'geheim')
      await changeRow(user, 'Umfang')
      const all = await screen.findByRole('radio', { name: 'Alle Buckets' })
      expect(all).toBeChecked()
      expect(all).toHaveAccessibleDescription(
        'Was später in der Quelle dazukommt, wird automatisch mit indiziert.',
      )
      await next(user)
      await nameItAndContinue(user, 'Objektspeicher')
      await create(user)

      await waitFor(() =>
        expect(mockCreateNewLibrary).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceSettings: expect.objectContaining({ allBuckets: true, scopes: [] }),
          }),
        ),
      )
    }, 30000)

    it('falls back to a selection where the key may not list the buckets', async () => {
      mockTestLibrarySource.mockResolvedValue({ reachable: true, message: 'Erreichbar.' })
      mockListS3Buckets.mockResolvedValue({ listingPermitted: false, buckets: [] })
      const user = await openS3Step()
      await user.type(screen.getByLabelText('Endpoint'), 'https://minio.intern.example:9000')
      await user.type(screen.getByLabelText('Access Key'), 'AKIAEXAMPLE')
      await user.type(screen.getByLabelText('Secret Key'), 'geheim')
      await changeRow(user, 'Umfang')

      await waitFor(() =>
        expect(screen.getByRole('radio', { name: 'Alle Buckets' })).toBeDisabled(),
      )
      expect(screen.getByRole('radio', { name: 'Alle Buckets' })).toHaveAccessibleDescription(
        /s3:ListAllMyBuckets/,
      )
      expect(
        screen.getByRole('radio', { name: 'Nur ausgewählte Buckets und Präfixe' }),
      ).toBeChecked()
      await next(user)
      expect(screen.getAllByText('Buckets und Präfixe fehlen').length).toBeGreaterThan(0)
    }, 30000)

    it('refuses overlapping scopes before anything is sent', async () => {
      mockTestLibrarySource.mockResolvedValue({ reachable: true, message: 'Erreichbar.' })
      mockListS3Buckets.mockResolvedValue({ listingPermitted: true, buckets: ['dokumente'] })
      const user = await openS3Step()
      await user.type(screen.getByLabelText('Endpoint'), 'https://minio.intern.example:9000')
      await user.type(screen.getByLabelText('Access Key'), 'AKIAEXAMPLE')
      await user.type(screen.getByLabelText('Secret Key'), 'geheim')
      await changeRow(user, 'Umfang')
      await onlySelected(user, 'Buckets und Präfixe')
      await chooseByHand(user, 'Buckets und Präfixe auswählen …', 'Bucket/Präfix', [
        'dokumente/2025',
        'dokumente/2025/q1',
      ])
      await next(user)

      expect(screen.queryByLabelText(/^Name/)).not.toBeInTheDocument()
      expect(
        screen.getAllByText(/„dokumente\/2025\/“ und „dokumente\/2025\/q1\/“ überschneiden sich/)
          .length,
      ).toBeGreaterThan(0)
      expect(screen.queryByLabelText(/^Name/)).not.toBeInTheDocument()
      expect(mockCreateNewLibrary).not.toHaveBeenCalled()
    }, 25000)
  })

  describe('Confluence origin (#1135, ADR-0023)', () => {
    const spaces = [
      { key: 'BAU', name: 'Bauamt' },
      { key: 'HR', name: 'Personal' },
      { key: 'IT', name: 'IT-Betrieb' },
    ]
    const DC_DETECTED = {
      reachable: true,
      details: { edition: 'DATA_CENTER' },
      credentialsVerified: false,
      message: 'Confluence Data Center erkannt.',
    }
    const DC_VERIFIED = {
      reachable: true,
      details: { edition: 'DATA_CENTER' },
      credentialsVerified: true,
      message: 'Confluence Data Center erreichbar, Zugangsdaten gültig.',
    }

    beforeEach(() => {
      mockTestLibrarySource.mockReset()
      mockListConfluenceSpaces.mockReset()
      mockListConfluenceSpaces.mockResolvedValue({ spaces })
    })

    async function openConfluenceStep() {
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Confluence/)
      return user
    }

    /** Types the address; the edition is detected from it after a short pause in typing. */
    async function enterAddress(user: User, url: string, edition: 'Cloud' | 'Data Center') {
      await user.type(screen.getByLabelText(/^Adresse/), url)
      expect(await screen.findByText(`Edition: ${edition}`)).toBeInTheDocument()
    }

    /**
     * A Data Center instance with its token; „Umfang“ is opened, which tests the connection, and
     * switched to a selection of its own unless `all` keeps „Alle Spaces“.
     */
    async function connectDataCenter(user: User, all = false) {
      await enterAddress(user, 'https://wiki.behoerde.example/confluence', 'Data Center')
      await user.type(screen.getByLabelText(/^Persönliches Zugriffstoken$/), 'pat-geheim')
      await changeRow(user, 'Umfang')
      if (!all) await onlySelected(user, 'Spaces')
    }

    it('creates „Alle Spaces“ without listing a single space', async () => {
      mockTestLibrarySource.mockResolvedValueOnce(DC_DETECTED).mockResolvedValue(DC_VERIFIED)
      const user = await openConfluenceStep()
      await connectDataCenter(user, true)

      const all = screen.getByRole('radio', { name: 'Alle Spaces' })
      expect(all).toBeChecked()
      expect(all).toHaveAccessibleDescription(
        'Was später in der Quelle dazukommt, wird automatisch mit indiziert.',
      )
      await next(user)
      await nameItAndContinue(user, 'Wiki')
      await create(user)

      await waitFor(() =>
        expect(mockCreateNewLibrary).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceType: 'CONFLUENCE',
            sourceSettings: expect.objectContaining({ allSpaces: true, spaces: [] }),
          }),
        ),
      )
      expect(mockListConfluenceSpaces).not.toHaveBeenCalled()
    }, 30000)

    it('detects the edition from the address and shows Cloud fields for Cloud', async () => {
      mockTestLibrarySource.mockResolvedValueOnce({
        reachable: true,
        details: { edition: 'CLOUD' },
        credentialsVerified: false,
        message:
          'Confluence Cloud erkannt. Geben Sie E-Mail-Adresse und API-Token des Dienstkontos ein.',
      })
      const user = await openConfluenceStep()

      expect(screen.queryByText(/^Edition:/)).not.toBeInTheDocument()
      await enterAddress(user, 'https://site.atlassian.net/wiki', 'Cloud')

      expect(mockTestLibrarySource).toHaveBeenCalledWith({
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://site.atlassian.net/wiki',
      })
      expect(screen.getByLabelText(/^E-Mail-Adresse/)).toBeInTheDocument()
      expect(screen.getByLabelText(/^API-Token$/)).toBeInTheDocument()
      expect(screen.queryByLabelText(/Persönliches Zugriffstoken/)).not.toBeInTheDocument()
      // the scope waits in its own row, preselected to every space the access sees
      expect(within(rowOf('Umfang')).getByText('Alle Spaces')).toBeInTheDocument()
    }, 15000)

    it('shows the token field for Data Center and refuses to continue while the test fails', async () => {
      mockTestLibrarySource.mockResolvedValueOnce(DC_DETECTED).mockResolvedValue({
        reachable: false,
        details: { edition: 'DATA_CENTER' },
        credentialsVerified: false,
        message: 'Anmeldung abgelehnt.',
      })
      const user = await openConfluenceStep()
      await enterAddress(user, 'https://wiki.behoerde.example/confluence', 'Data Center')

      expect(screen.getByLabelText(/^Persönliches Zugriffstoken$/)).toBeInTheDocument()
      expect(screen.queryByLabelText(/E-Mail-Adresse/)).not.toBeInTheDocument()

      await next(user)
      expect(screen.getByText('Persönliches Zugriffstoken fehlt')).toBeInTheDocument()
      expect(screen.queryByText('Spaces fehlen')).not.toBeInTheDocument()

      await user.type(screen.getByLabelText(/^Persönliches Zugriffstoken$/), 'pat-falsch')
      await changeRow(user, 'Umfang')
      await onlySelected(user, 'Spaces')
      await user.click(await screen.findByRole('checkbox', { name: 'Bauamt' }))
      await next(user)

      const summary = await screen.findByRole('alert', { name: 'Fehler: 1 Angabe prüfen' })
      expect(within(summary).getByRole('button')).toHaveTextContent('Verbindung: Test (Anmeldung)')
      // the step stays, the source fields are still there
      expect(screen.getByLabelText(/^Adresse/)).toBeInTheDocument()
      expect(screen.queryByLabelText(/^Name/)).not.toBeInTheDocument()
    }, 20000)

    it('verifies credentials, loads the spaces, and sends edition, credentials and selection', async () => {
      mockTestLibrarySource.mockResolvedValueOnce(DC_DETECTED).mockResolvedValueOnce(DC_VERIFIED)
      const user = await openConfluenceStep()
      await connectDataCenter(user)

      expect(mockTestLibrarySource).toHaveBeenLastCalledWith({
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        sourceSettings: { edition: 'DATA_CENTER' },
        sourceCredentials: 'pat-geheim',
        libraryId: undefined,
      })
      await user.click(await screen.findByRole('checkbox', { name: 'Bauamt' }))
      expect(within(rowOf('Verbindung')).getByText('verbunden')).toBeInTheDocument()
      await next(user)
      // A single space prefills the name.
      expect(screen.getByLabelText(/^Name/)).toHaveValue('Bauamt')
      await nameItAndContinue(user, 'Wiki Bauamt')
      await create(user)

      // The whole request, not just an excerpt: a field the creation endpoint does not
      // accept at all is only noticed if the comparison also sees the surplus. Without its own
      // full-sync rhythm, `sourceSettings.fullSyncIntervalDays` must therefore not be sent at all -
      // the 0 („zurück zur Vorgabe der Instanz") is known only to the edit path, creation rejects
      // it with 400 (KnowledgeLibraryService: 1 to 365).
      await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
      expect(mockCreateNewLibrary).toHaveBeenCalledWith({
        name: 'Wiki Bauamt',
        description: undefined,
        ownerType: 'USER',
        ownerId: undefined,
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        sourceCredentials: 'pat-geheim',
        sourceSettings: { edition: 'DATA_CENTER', spaces: [{ key: 'BAU', name: 'Bauamt' }] },
        // nightly is preset
        schedule: { frequency: 'DAILY', hour: 2, minute: 0, weekday: undefined },
      })
      // the passed test is not repeated on „Weiter“
      expect(mockTestLibrarySource).toHaveBeenCalledTimes(2)
      // The "Erste Indizierung sofort ..." switch defaults to on - the first run starts right
      // after creation, before the navigation to the detail page.
      expect(mockTriggerIndexing).toHaveBeenCalledWith('lib-neu', 'CONFLUENCE')
      expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu?justCreated=1')
    }, 25000)

    // An entered rhythm is sent along; only the empty field means „kein eigener Rhythmus"
    // on creation and must not send the field as 0.
    it('sends an entered full-sync rhythm as the number, not as the update path’s zero', async () => {
      mockTestLibrarySource.mockResolvedValueOnce(DC_DETECTED).mockResolvedValueOnce(DC_VERIFIED)
      const user = await openConfluenceStep()
      await connectDataCenter(user)
      await user.click(await screen.findByRole('checkbox', { name: 'Bauamt' }))
      await changeRow(user, 'Zeitplan')
      await user.type(screen.getByLabelText(/Vollabgleich alle/), '14')
      await next(user)
      await nameItAndContinue(user, 'Wiki Bauamt')
      await create(user)

      await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
      expect(mockCreateNewLibrary.mock.calls[0][0]).toMatchObject({
        sourceSettings: { fullSyncIntervalDays: 14 },
      })
    }, 25000)

    it('skips the first run when the immediate-indexing switch is turned off', async () => {
      mockTestLibrarySource.mockResolvedValueOnce(DC_DETECTED).mockResolvedValueOnce(DC_VERIFIED)
      const user = await openConfluenceStep()
      await connectDataCenter(user)
      await user.click(await screen.findByRole('checkbox', { name: 'Bauamt' }))

      await changeRow(user, 'Zeitplan')
      await user.click(
        screen.getByRole('switch', { name: 'Erste Indizierung sofort nach dem Anlegen' }),
      )
      await next(user)
      await nameItAndContinue(user, 'Wiki Bauamt')
      await create(user)

      await waitFor(() =>
        expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu?justCreated=1'),
      )
      expect(mockTriggerIndexing).not.toHaveBeenCalled()
    }, 25000)

    it('joins e-mail and token for Cloud and drops verification when the address changes', async () => {
      const cloud = (credentialsVerified: boolean) => ({
        reachable: true,
        details: { edition: 'CLOUD' },
        credentialsVerified,
        message: credentialsVerified ? 'Zugangsdaten gültig.' : 'Confluence Cloud erkannt.',
      })
      mockTestLibrarySource
        .mockResolvedValueOnce(cloud(false))
        .mockResolvedValueOnce(cloud(true))
        .mockResolvedValueOnce(cloud(false))
        .mockResolvedValueOnce(cloud(true))
      const user = await openConfluenceStep()
      await enterAddress(user, 'https://site.atlassian.net', 'Cloud')
      await user.type(screen.getByLabelText(/^E-Mail-Adresse/), 'dienst@behoerde.example')
      await user.type(screen.getByLabelText(/^API-Token$/), 'tok-123')
      await changeRow(user, 'Umfang')

      expect(mockTestLibrarySource).toHaveBeenLastCalledWith(
        expect.objectContaining({ sourceCredentials: 'dienst@behoerde.example:tok-123' }),
      )
      expect(await within(rowOf('Verbindung')).findByText('verbunden')).toBeInTheDocument()

      // a changed address invalidates edition and test result alike
      await changeRow(user, 'Verbindung')
      await user.type(screen.getByLabelText(/^Adresse/), '/wiki')
      expect(screen.queryByText('Edition: Cloud')).not.toBeInTheDocument()
      // detected again for the new address, then tested again on leaving the row
      expect(await screen.findByText('Edition: Cloud')).toBeInTheDocument()
      await changeRow(user, 'Umfang')
      await waitFor(() => expect(mockTestLibrarySource).toHaveBeenCalledTimes(4))
      expect(mockTestLibrarySource).toHaveBeenLastCalledWith(
        expect.objectContaining({ sourceUrl: 'https://site.atlassian.net/wiki' }),
      )
    }, 25000)

    it('shows a blocked or unreachable address with the backend wording and does not go on without an edition', async () => {
      mockTestLibrarySource.mockResolvedValueOnce({
        reachable: false,
        details: { edition: null },
        credentialsVerified: false,
        message:
          'Die Adresse zeigt auf ein internes Ziel. Interne Ziele müssen in OPAA_INDEXING_TARGET_ALLOWLIST freigegeben sein.',
      })
      const user = await openConfluenceStep()
      await user.type(screen.getByLabelText(/^Adresse/), 'https://10.0.0.5/confluence')

      expect(
        await screen.findByText(/OPAA_INDEXING_TARGET_ALLOWLIST freigegeben/),
      ).toBeInTheDocument()
      expect(screen.queryByText(/^Edition:/)).not.toBeInTheDocument()
      await next(user)
      expect(screen.getByText('Edition nicht erkannt, Adresse prüfen')).toBeInTheDocument()
      expect(screen.queryByLabelText(/^Name/)).not.toBeInTheDocument()
    }, 15000)

    it('keeps the space selection usable after "Zurück" re-enters the source step', async () => {
      mockTestLibrarySource.mockResolvedValue(DC_VERIFIED)
      const user = await openConfluenceStep()
      await connectDataCenter(user)
      await user.click(await screen.findByRole('checkbox', { name: 'Bauamt' }))
      await next(user)
      await user.click(screen.getByRole('button', { name: 'Zurück' }))

      expect(within(rowOf('Umfang')).getByText('Bauamt')).toBeInTheDocument()
      await changeRow(user, 'Umfang')
      // the reopened row reloads the listing for the still-verified credentials
      await waitFor(() => expect(mockListConfluenceSpaces).toHaveBeenCalledTimes(2))
      expect(await screen.findByRole('checkbox', { name: 'Bauamt' })).toBeChecked()
      await user.click(screen.getByRole('checkbox', { name: 'Personal' }))
      expect(screen.getByRole('checkbox', { name: 'Personal' })).toBeChecked()
    }, 25000)

    it('drops a late test answer once the address changed in the meantime', async () => {
      let answer: (value: unknown) => void = () => {}
      mockTestLibrarySource
        .mockResolvedValueOnce(DC_DETECTED)
        .mockImplementationOnce(() => new Promise((resolve) => (answer = resolve)))
        .mockImplementation(() => new Promise(() => {}))
      const user = await openConfluenceStep()
      await enterAddress(user, 'https://wiki.behoerde.example/confluence', 'Data Center')
      await user.type(screen.getByLabelText(/^Persönliches Zugriffstoken$/), 'pat-geheim')
      await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
      expect(screen.getByRole('button', { name: 'Wird geprüft …' })).toHaveAttribute(
        'aria-disabled',
        'true',
      )

      // the address changes while the answer for the old one is still pending
      await user.type(screen.getByLabelText(/^Adresse/), '/alt')
      await act(async () => {
        answer(DC_VERIFIED)
      })

      expect(
        screen.queryByText('Confluence Data Center erreichbar, Zugangsdaten gültig.'),
      ).not.toBeInTheDocument()
      expect(mockListConfluenceSpaces).not.toHaveBeenCalled()
      await next(user)
      expect(screen.getByText('Edition nicht erkannt, Adresse prüfen')).toBeInTheDocument()
    }, 25000)

    it('offers a retry when the space listing fails', async () => {
      mockTestLibrarySource.mockResolvedValue(DC_VERIFIED)
      mockListConfluenceSpaces
        .mockRejectedValueOnce(new Error('Confluence antwortete mit HTTP 502'))
        .mockResolvedValueOnce({ spaces })
      const user = await openConfluenceStep()
      await connectDataCenter(user)

      expect(await screen.findByText('Spaces ließen sich nicht laden.')).toBeInTheDocument()
      // the protocol code stays out of the sentence
      expect(screen.getByText('Confluence antwortete mit')).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Erneut laden' }))
      expect(await screen.findByRole('checkbox', { name: 'IT-Betrieb' })).toBeInTheDocument()
      expect(screen.queryByText('Spaces ließen sich nicht laden.')).not.toBeInTheDocument()
    }, 20000)
  })

  it('switches the connection form with the chosen kind and validates its fields', async () => {
    const user = userEvent.setup()
    await renderPage()

    const radiogroup = screen.getByRole('radiogroup', { name: 'Quellart wählen' })
    expect(radiogroup).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Upload/ })).toHaveAttribute('aria-checked', 'true')

    await chooseType(user, /Webverzeichnis/)
    expect(within(rowOf('Verbindung')).getByLabelText(/^Adresse/)).toBeInTheDocument()
    await next(user)
    expect(screen.getByText('Adresse fehlt')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    await chooseType(user, /Dateisystem/)
    expect(screen.queryByLabelText(/^Adresse/)).not.toBeInTheDocument()
    await user.type(screen.getByLabelText(/^Verzeichnis/), 'relativ/pfad')
    await next(user)
    expect(
      screen.getByText('Verzeichnis muss ein absoluter Pfad sein, z. B. /data/dokumente'),
    ).toBeInTheDocument()
  }, 20000)

  // docs/design/accessibility.md 2.7, as in the Bearbeiten-Dialog of the Reiter „Quelle"
  describe('errors at their field in „Quelle einrichten“ (#2193)', () => {
    it('ties a wrong path to its field, focuses it and lets the step stand', async () => {
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Dateisystem/)
      const path = screen.getByLabelText(/^Verzeichnis/)
      await user.type(path, 'relativ/pfad')

      await next(user)

      expect(path).toHaveAttribute('aria-invalid', 'true')
      expect(path).toHaveAccessibleDescription(/muss ein absoluter Pfad sein/)
      // the gathered summary takes the focus; its entry leads to the field
      const summary = screen.getByRole('alert', { name: 'Fehler: 1 Angabe prüfen' })
      await waitFor(() => expect(summary).toHaveFocus())
      await user.click(within(summary).getByRole('button', { name: 'Verbindung: Verzeichnis' }))
      await waitFor(() => expect(path).toHaveFocus())
      expect(
        screen.getByRole('heading', { level: 2, name: 'Quelle einrichten' }),
      ).toBeInTheDocument()
    }, 15000)

    it('ties a missing address to the URL field', async () => {
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Webverzeichnis/)

      await next(user)

      const url = screen.getByLabelText(/^Adresse/)
      expect(url).toHaveAccessibleDescription(/Adresse fehlt/)
      const summary = screen.getByRole('alert', { name: 'Fehler: 1 Angabe prüfen' })
      await user.click(within(summary).getByRole('button', { name: 'Verbindung: Adresse' }))
      await waitFor(() => expect(url).toHaveFocus())
    }, 15000)

    it('ties a missing S3 access key to its field', async () => {
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /S3-Objektspeicher/)
      await user.type(screen.getByLabelText('Endpoint'), 'https://minio.intern.example:9000')

      await next(user)

      const accessKey = screen.getByLabelText('Access Key')
      expect(accessKey).toHaveAccessibleDescription(/Access Key fehlt/)
      const summary = screen.getByRole('alert', { name: /^Fehler: \d Angaben? prüfen$/ })
      await user.click(within(summary).getByRole('button', { name: 'Verbindung: Access Key' }))
      await waitFor(() => expect(accessKey).toHaveFocus())
    }, 15000)

    it('names an issue a choice under „Umfang“ causes under „Verbindung“ and leads to its field', async () => {
      mockListConnectionProfileOptions.mockResolvedValue([
        {
          id: 'profile-drive',
          name: 'Zugang Drive',
          sourceType: 'GOOGLE_DRIVE',
          serverUrl: 'https://www.googleapis.com',
          authMethod: 'SERVICE_ACCOUNT_KEY' as const,
          connectorDefaults: null,
        },
      ])
      mockBrowseSource.mockResolvedValue({
        complete: true,
        entries: [{ key: 'myDrive', name: 'Meine Ablage' }],
      })
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Google Drive/)
      await changeRow(user, 'Umfang')
      await onlySelected(user, 'Geteilte Ablagen und Ordner')
      await user.click(await screen.findByRole('checkbox', { name: /Meine Ablage/ }))

      await next(user)

      const summary = screen.getByRole('alert', { name: 'Fehler: 1 Angabe prüfen' })
      await user.click(
        within(summary).getByRole('button', { name: 'Verbindung: Imitiertes Konto' }),
      )
      const subject = screen.getByLabelText(/^Imitiertes Konto/)
      await waitFor(() => expect(subject).toHaveFocus())
      expect(subject).toHaveAccessibleDescription(/„Meine Ablage“ braucht ein imitiertes Konto/)
    }, 20000)
  })

  // A result belongs to the probe it was measured with - including its source type.
  it('drops a connection test result when the source type changes (#514)', async () => {
    const user = userEvent.setup()
    mockTestLibrarySource.mockResolvedValueOnce({
      reachable: true,
      message: 'Feed erreichbar, 12 Einträge gefunden.',
    })
    await renderPage()

    await chooseType(user, /RSS-Feed/)
    await user.type(screen.getByLabelText(/Adresse/), 'https://example.test/feed.xml')
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    expect(await screen.findByText('Feed erreichbar, 12 Einträge gefunden.')).toBeInTheDocument()

    // The same address value, another source type: the probe never ran against a web directory.
    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    await chooseType(user, /Webverzeichnis/)

    expect(screen.getByLabelText(/Adresse/)).toHaveValue('https://example.test/feed.xml')
    expect(screen.queryByText('Feed erreichbar, 12 Einträge gefunden.')).not.toBeInTheDocument()
  }, 15000)

  it('keeps entered values when navigating back', async () => {
    const user = userEvent.setup()
    await renderPage()

    await chooseType(user, /Webverzeichnis/)
    await user.type(
      screen.getByLabelText(/^Adresse/),
      'https://intranet.behoerde.example/merkblaetter/',
    )
    await next(user)
    await user.type(screen.getByLabelText(/^Name/), ' Merkblätter')
    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    expect(within(rowOf('Verbindung')).getByText('intranet.behoerde.example')).toBeInTheDocument()
    await changeRow(user, 'Verbindung')
    expect(screen.getByLabelText(/^Adresse/)).toHaveValue(
      'https://intranet.behoerde.example/merkblaetter/',
    )
    await next(user)
    expect(screen.getByLabelText(/^Name/)).toHaveValue('intranet.behoerde.example Merkblätter')
  }, 20000)

  // A library is created with no reach of its own - the wizard picks no release level, because
  // there is none to pick.
  it('creates the library and navigates to its detail page', async () => {
    const user = userEvent.setup()
    await renderPage()

    await next(user)
    await nameItAndContinue(user, 'Rechtsquellen Soziales')

    expect(screen.queryByRole('combobox', { name: /Verteilungsstufe/ })).not.toBeInTheDocument()

    await create(user)

    await waitFor(() => {
      expect(mockCreateNewLibrary).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'Rechtsquellen Soziales', sourceType: 'UPLOAD' }),
      )
    })
    expect(mockNavigate).toHaveBeenCalledWith('/libraries/lib-neu?justCreated=1')
  }, 15000)

  it('creates a group-owned library, offering only the groups returned for the user', async () => {
    mockGetMyGroups.mockResolvedValue([
      {
        id: 'group-referat-50',
        name: 'Referat 50',
        description: null,
        kind: 'ORG_UNIT' as const,
        externalId: 'directory-guid',
        parentGroupId: null,
        memberCount: 3,
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    const user = userEvent.setup()
    await renderPage()

    await next(user)
    await nameItAndContinue(user, 'Team-Bibliothek')
    await user.click(screen.getByRole('radio', { name: /eine gruppe/i }))
    await user.click(await screen.findByRole('combobox', { name: 'Gruppe' }))
    await user.click(await screen.findByRole('option', { name: 'Referat 50' }))
    await create(user)

    await waitFor(() => {
      expect(mockCreateNewLibrary).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Team-Bibliothek',
          ownerType: 'GROUP',
          ownerId: 'group-referat-50',
        }),
      )
    })
  }, 20000)

  it('shows a visible hint instead of a silent empty picker when the caller has no groups', async () => {
    const user = userEvent.setup()
    await renderPage()

    await next(user)
    await nameItAndContinue(user, 'Rechtsquellen Soziales')
    await user.click(screen.getByRole('radio', { name: /eine gruppe/i }))

    expect(await screen.findByText(/keiner gruppe mitglied/i)).toBeInTheDocument()
  }, 15000)

  describe('Zugang (#2162)', () => {
    const profile = {
      id: 'profile-intern',
      name: 'Nextcloud intern',
      sourceType: 'NEXTCLOUD',
      serverUrl: 'https://cloud.intern.example',
      authMethod: 'PERSONAL_SECRET' as const,
      connectorDefaults: null,
    }

    function withNextcloud(overrides: Record<string, unknown>) {
      return mockSourceTypes.map((descriptor) =>
        descriptor.type === 'NEXTCLOUD' ? { ...descriptor, ...overrides } : descriptor,
      )
    }

    it('creates a library through the chosen profile, its address prefilled', async () => {
      mockListConnectionProfileOptions.mockResolvedValue([profile])
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)

      expect(mockListConnectionProfileOptions).toHaveBeenCalledWith('NEXTCLOUD')
      const choice = await screen.findByRole('radiogroup', { name: 'Zugang' })
      // the choice stands above the source fields, the own address as its last line
      expect(
        choice.compareDocumentPosition(screen.getByLabelText(/^Adresse/)) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy()
      const radios = within(choice).getAllByRole('radio')
      expect(radios[radios.length - 1]).toHaveAccessibleName('Eigene Adresse')
      // the profile names its server next to its name
      expect(within(choice).getByRole('radio', { name: /Nextcloud intern/ })).toHaveAccessibleName(
        /cloud\.intern\.example/,
      )

      await user.click(screen.getByRole('radio', { name: /Nextcloud intern/ }))
      expect(screen.getByLabelText(/^Adresse/)).toHaveValue('https://cloud.intern.example')
      await user.type(screen.getByLabelText(/^Benutzername/), 'svc-opaa')
      await user.type(screen.getByLabelText(/^App-Passwort$/), 'geheim')
      await next(user)
      await nameItAndContinue(user, 'Projektablage')
      await create(user)

      await waitFor(() =>
        expect(mockCreateNewLibrary).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceType: 'NEXTCLOUD',
            connectionProfileId: 'profile-intern',
            sourceUrl: 'https://cloud.intern.example',
            sourceCredentials: 'svc-opaa:geheim',
          }),
        ),
      )
    }, 30000)

    it('does not carry address or fixed values of the previous way over to the next', async () => {
      const other = {
        ...profile,
        id: 'profile-neu',
        name: 'Nextcloud neu',
        serverUrl: 'https://cloud.neu.example',
      }
      mockListConnectionProfileOptions.mockResolvedValue([profile, other])
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)
      const address = () => screen.getByLabelText(/^Adresse/)

      // an own address outside the profile chosen next does not stay
      await user.click(await screen.findByRole('radio', { name: /Eigene Adresse/ }))
      await user.type(address(), 'https://nc.extern.example')
      await user.click(await screen.findByRole('radio', { name: /Nextcloud intern/ }))
      expect(address()).toHaveValue('https://cloud.intern.example')

      // an address under the previous profile does not follow to the next one
      await user.type(address(), '/remote.php/dav')
      await user.click(screen.getByRole('radio', { name: /Nextcloud neu/ }))
      expect(address()).toHaveValue('https://cloud.neu.example')

      // nor to the own address
      await user.click(screen.getByRole('radio', { name: /Eigene Adresse/ }))
      expect(address()).toHaveValue('')
    }, 25000)

    it('drops the edition detected for an own address once a profile clears that address', async () => {
      mockListSourceTypes.mockResolvedValue(
        mockSourceTypes.map((descriptor) =>
          descriptor.type === 'CONFLUENCE'
            ? { ...descriptor, profileSupport: 'OPTIONAL' as const }
            : descriptor,
        ),
      )
      mockListConnectionProfileOptions.mockResolvedValue([
        {
          ...profile,
          id: 'profile-wiki',
          name: 'Wiki intern',
          sourceType: 'CONFLUENCE',
          serverUrl: 'https://wiki.intern.example',
        },
      ])
      mockTestLibrarySource.mockResolvedValueOnce({
        reachable: true,
        details: { edition: 'CLOUD' },
        credentialsVerified: false,
        message: 'Confluence Cloud erkannt.',
      })
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Confluence/)
      await screen.findByRole('radio', { name: /Wiki intern/ })

      await user.click(screen.getByRole('radio', { name: /Eigene Adresse/ }))
      await user.type(screen.getByLabelText(/^Adresse/), 'https://site.atlassian.net/wiki')
      expect(await screen.findByText('Edition: Cloud')).toBeInTheDocument()

      await user.click(screen.getByRole('radio', { name: /Wiki intern/ }))
      expect(screen.getByLabelText(/^Adresse/)).toHaveValue('https://wiki.intern.example')
      expect(screen.queryByText('Edition: Cloud')).not.toBeInTheDocument()
      // the profile's address is detected afresh
      await waitFor(() =>
        expect(mockTestLibrarySource).toHaveBeenLastCalledWith(
          expect.objectContaining({ sourceUrl: 'https://wiki.intern.example' }),
        ),
      )
    }, 25000)

    it('offers no certificate switch under the chosen profile and sends none', async () => {
      mockListConnectionProfileOptions.mockResolvedValue([
        {
          ...profile,
        },
      ])
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)
      await user.click(await screen.findByRole('radio', { name: /Nextcloud intern/ }))

      expect(screen.queryByRole('switch', { name: /Zertifikatsprüfung/ })).not.toBeInTheDocument()
      await user.type(screen.getByLabelText(/^Benutzername/), 'svc-opaa')
      await user.type(screen.getByLabelText(/^App-Passwort$/), 'geheim')
      await next(user)
      await nameItAndContinue(user, 'Projektablage')
      await create(user)

      await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
      const request = mockCreateNewLibrary.mock.calls[0][0]
      expect(request.connectionProfileId).toBe('profile-intern')
      expect(request).not.toHaveProperty('sourceInsecureSsl')
    }, 30000)

    it('sends no profile for the own address', async () => {
      mockListConnectionProfileOptions.mockResolvedValue([profile])
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)
      await user.click(await screen.findByRole('radio', { name: /Eigene Adresse/ }))

      await user.type(screen.getByLabelText(/^Adresse/), 'https://cloud.example')
      await user.type(screen.getByLabelText(/^Benutzername/), 'svc')
      await user.type(screen.getByLabelText(/^App-Passwort$/), 'pw')
      await next(user)
      await nameItAndContinue(user, 'Eigene Cloud')
      await create(user)

      await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
      expect(mockCreateNewLibrary.mock.calls[0][0]).not.toHaveProperty('connectionProfileId')
    }, 25000)

    it('lets a type released only through a profile be chosen, without the own address', async () => {
      mockListSourceTypes.mockResolvedValue(
        withNextcloud({ creatable: true, creatableWithOwnAddress: false }),
      )
      mockListConnectionProfileOptions.mockResolvedValue([profile])
      const user = userEvent.setup()
      await renderPage()

      const tile = screen.getByRole('radio', { name: /Nextcloud/ })
      expect(tile).toBeEnabled()
      expect(tile).not.toHaveTextContent(/bietet dieser Assistent noch nicht/)
      await chooseType(user, /Nextcloud/)

      // the one way needs no choice: it is taken, its address prefilled
      expect(await screen.findByLabelText(/^Adresse/)).toHaveValue('https://cloud.intern.example')
      expect(screen.getByLabelText(/^Adresse/)).toHaveAccessibleDescription(
        /Zugangs „Nextcloud intern“/,
      )
      expect(screen.queryByRole('radiogroup', { name: 'Zugang' })).not.toBeInTheDocument()
      expect(screen.queryByRole('radio', { name: /Eigene Adresse/ })).not.toBeInTheDocument()
    }, 15000)

    it('names the system administration and does not go on when no way is left', async () => {
      mockListSourceTypes.mockResolvedValue(
        withNextcloud({ profileRequired: true, creatable: true, creatableWithOwnAddress: false }),
      )
      mockListConnectionProfileOptions.mockResolvedValue([])
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)

      expect(await screen.findByTestId('library-create-connection-none')).toHaveTextContent(
        /die Systemverwaltung richtet Zugänge ein/,
      )
      expect(screen.queryByLabelText(/^Adresse/)).not.toBeInTheDocument()
      await next(user)
      expect(
        screen.getByText('Für diese Quellart steht Ihnen kein Zugang zur Verfügung.'),
      ).toBeInTheDocument()
    }, 15000)

    it('hides a type that is usable only through a profile while none is usable', async () => {
      mockListSourceTypes.mockResolvedValue(
        withNextcloud({
          profileRequired: true,
          creatable: false,
          creatableWithOwnAddress: false,
          creationNotice:
            'Die Quellart „Nextcloud“ ist nur über einen Zugang nutzbar, und es gibt noch keinen. Zugänge legt die Systemverwaltung an.',
        }),
      )
      await renderPage()

      expect(await screen.findByRole('radio', { name: /Confluence/ })).toBeEnabled()
      expect(screen.queryByRole('radio', { name: /Nextcloud/ })).not.toBeInTheDocument()
      expect(screen.queryByText(/es gibt noch keinen/)).not.toBeInTheDocument()
    }, 15000)

    it('offers the own address alone while no profile exists yet', async () => {
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)

      await screen.findByLabelText(/^Adresse/)
      expect(screen.queryByRole('radiogroup', { name: 'Zugang' })).not.toBeInTheDocument()
      expect(screen.queryByRole('radio', { name: /Eigene Adresse/ })).not.toBeInTheDocument()
    }, 15000)

    it('asks for no profile for a type that admits none', async () => {
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Dateisystem/)

      expect(screen.queryByTestId('library-create-connection')).not.toBeInTheDocument()
      expect(mockListConnectionProfileOptions).not.toHaveBeenCalled()
    }, 15000)
  })

  describe('SharePoint (ADR-0047)', () => {
    const sharePointProfile = {
      id: 'profile-sharepoint',
      name: 'SharePoint Rheinfurt',
      sourceType: 'SHAREPOINT',
      serverUrl: 'https://graph.microsoft.com',
      authMethod: 'CLIENT_CREDENTIALS' as const,
      connectorDefaults: null,
    }

    it('creates a library through the profile with the chosen document library, without a secret', async () => {
      mockListConnectionProfileOptions.mockResolvedValue([sharePointProfile])
      mockBrowseSource.mockImplementation(
        async (_type: string, request: { query?: Record<string, unknown> }) =>
          request.query?.siteUrl
            ? { complete: true, entries: [{ key: 'site:host,a,b', name: 'Bauamt' }] }
            : request.query?.site
              ? { complete: true, entries: [{ key: 'drive:b!bauamt', name: 'Dokumente' }] }
              : { complete: true, entries: [] },
      )
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /SharePoint/)

      expect(mockListConnectionProfileOptions).toHaveBeenCalledWith('SHAREPOINT')
      // nothing to fill in under „Verbindung“: it names the Zugang, and „Umfang“ opens at once
      await onlySelected(user, 'Dokumentbibliotheken und Ordner')
      await user.click(
        screen.getByRole('button', { name: 'Dokumentbibliotheken und Ordner auswählen …' }),
      )
      const dialog = await screen.findByRole('dialog', {
        name: 'Dokumentbibliotheken und Ordner auswählen',
      })

      // nothing chosen yet: the step does not go on
      await user.click(within(dialog).getByRole('button', { name: 'Abbrechen' }))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(within(rowOf('Verbindung')).getByText('Zugang „SharePoint Rheinfurt“')).toBeVisible()
      expect(screen.queryByRole('radio', { name: /Eigene Adresse/ })).not.toBeInTheDocument()
      await next(user)
      expect(screen.getByText('Dokumentbibliotheken fehlen')).toBeInTheDocument()
      // the row stays open at its issue; the selection opens from its link
      await user.click(
        screen.getByRole('button', { name: 'Dokumentbibliotheken und Ordner auswählen …' }),
      )
      const reopened = await screen.findByRole('dialog')
      await user.click(within(reopened).getByRole('button', { name: 'Von Hand eintragen …' }))
      await user.type(
        within(reopened).getByLabelText('Adresse der Site'),
        'https://rheinfurt.sharepoint.com/sites/bauamt',
      )
      await user.click(within(reopened).getByRole('button', { name: 'Öffnen' }))
      await user.click(await within(reopened).findByRole('checkbox', { name: 'Dokumente' }))
      await user.click(within(reopened).getByRole('button', { name: 'Übernehmen' }))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

      // the rhythm names the type's own default (one day for SharePoint), in no other source's terms
      await changeRow(user, 'Zeitplan')
      const rhythm = screen.getByLabelText(/Vollabgleich alle/)
      expect(rhythm).toHaveAttribute('placeholder', '1')
      expect(rhythm).toHaveAccessibleDescription(/^Leer = Vorgabe der Instanz \(täglich\)\./)
      expect(rhythm).not.toHaveAccessibleDescription(/Confluence/)

      await next(user)
      expect(screen.getByLabelText(/^Name/)).toHaveValue('Dokumente (Bauamt)')
      await nameItAndContinue(user, 'Bauamt Dokumente')
      await create(user)

      await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
      const request = mockCreateNewLibrary.mock.calls[0][0]
      expect(request).toMatchObject({
        sourceType: 'SHAREPOINT',
        connectionProfileId: 'profile-sharepoint',
        sourceUrl: 'https://graph.microsoft.com',
        sourceSettings: { libraries: [{ driveId: 'b!bauamt', name: 'Dokumente (Bauamt)' }] },
      })
      expect(request.sourceCredentials).toBeUndefined()
      expect(mockBrowseSource).toHaveBeenCalledWith(
        'SHAREPOINT',
        expect.objectContaining({
          connectionProfileId: 'profile-sharepoint',
          query: { siteUrl: 'https://rheinfurt.sharepoint.com/sites/bauamt' },
        }),
      )
    }, 30000)

    it('takes all document libraries of a chosen site', async () => {
      mockListConnectionProfileOptions.mockResolvedValue([sharePointProfile])
      mockBrowseSource.mockImplementation(
        async (_type: string, request: { query?: Record<string, unknown> }) =>
          request.query?.siteUrl
            ? { complete: true, entries: [{ key: 'site:host,a,b', name: 'Bauamt' }] }
            : { complete: true, entries: [] },
      )
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /SharePoint/)

      const all = await screen.findByRole('radio', { name: 'Alle Dokumentbibliotheken einer Site' })
      expect(all).toBeChecked()
      expect(all).toHaveAccessibleDescription(
        'Was später in der Quelle dazukommt, wird automatisch mit indiziert.',
      )
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      await chooseByHand(user, 'Sites auswählen …', 'Adresse der Site', [
        'https://rheinfurt.sharepoint.com/sites/bauamt',
      ])
      await next(user)
      expect(screen.getByLabelText(/^Name/)).toHaveValue('Bauamt')
      await nameItAndContinue(user, 'Bauamt')
      await create(user)

      await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
      expect(mockCreateNewLibrary.mock.calls[0][0].sourceSettings).toEqual({
        libraries: [{ site: { id: 'host,a,b', name: 'Bauamt' } }],
      })
    }, 30000)

    it('cannot be created without a profile', async () => {
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /SharePoint/)

      expect(await screen.findByTestId('library-create-connection-none')).toHaveTextContent(
        'Für die Quellart „SharePoint“ steht Ihnen kein Zugang zur Verfügung; die Systemverwaltung richtet Zugänge ein.',
      )
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      await next(user)
      expect(
        screen.getByText('Für diese Quellart steht Ihnen kein Zugang zur Verfügung.'),
      ).toBeInTheDocument()
    }, 20000)
  })

  describe('Private Bibliothek (#2164)', () => {
    const profile = {
      id: 'profile-intern',
      name: 'Nextcloud intern',
      sourceType: 'NEXTCLOUD',
      serverUrl: 'https://cloud.intern.example',
      authMethod: 'PERSONAL_SECRET' as const,
      ownership: 'BOTH' as const,
      ownAccount: false,
      connectorDefaults: null,
    }
    const ownProfile = { ...profile, ownAccount: true }

    it('offers only the private way on a profile for persons only', async () => {
      mockListConnectionProfileOptions.mockResolvedValue([
        { ...ownProfile, id: 'profile-personen', name: 'Nextcloud Personen', ownership: 'PERSON' },
      ])
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)

      const group = await screen.findByRole('radiogroup', { name: 'Zugang' })
      expect(
        within(group).getByRole('radio', { name: /Nextcloud Personen · privat/ }),
      ).toHaveAccessibleName(/nur für Sie, später nicht änderbar/)
      expect(
        screen.queryByRole('radio', { name: /^Nextcloud Personen(?! · privat)/ }),
      ).not.toBeInTheDocument()
    }, 20000)

    it('never chooses the private way by itself, even where it is the only one', async () => {
      mockListSourceTypes.mockResolvedValue(
        mockSourceTypes.map((descriptor) =>
          descriptor.type === 'NEXTCLOUD'
            ? { ...descriptor, creatable: true, creatableWithOwnAddress: false }
            : descriptor,
        ),
      )
      mockListConnectionProfileOptions.mockResolvedValue([
        { ...ownProfile, id: 'profile-personen', name: 'Nextcloud Personen', ownership: 'PERSON' },
      ])
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)

      const only = await screen.findByRole('radio', { name: /Nextcloud Personen · privat/ })
      expect(only).not.toBeChecked()
      await next(user)
      expect(await screen.findByText('Bitte wählen Sie einen Zugang.')).toBeVisible()
      await user.click(only)
      expect(only).toBeChecked()
    }, 20000)

    it('offers no private way, and no hint to connect an account, without a connected account', async () => {
      mockListConnectionProfileOptions.mockResolvedValue([profile])
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)
      await screen.findByRole('radio', { name: /Nextcloud intern/ })

      expect(screen.queryByText(/nur für Sie/)).not.toBeInTheDocument()
      expect(screen.queryByText(/verbundene/i)).not.toBeInTheDocument()
      expect(screen.queryByRole('radio', { name: /privat/ })).not.toBeInTheDocument()
    }, 20000)

    it('creates a private library over the own account, without a secret and without „Freigaben“', async () => {
      mockListConnectionProfileOptions.mockResolvedValue([ownProfile])
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)

      const group = await screen.findByRole('radiogroup', { name: 'Zugang' })
      await user.click(within(group).getByRole('radio', { name: /Nextcloud intern · privat/ }))

      expect(screen.queryByLabelText(/^Benutzername/)).not.toBeInTheDocument()
      expect(screen.queryByLabelText(/^App-Passwort/)).not.toBeInTheDocument()
      expect(screen.getByText('3 · Name')).toBeInTheDocument()
      expect(screen.getByText('4 · Prüfen')).toBeInTheDocument()
      expect(screen.queryByText(/· Freigaben/)).not.toBeInTheDocument()

      await next(user)
      const field = screen.getByLabelText(/^Name/)
      await user.clear(field)
      await user.type(field, 'Meine Ablage')
      expect(screen.getByText(/Nur Sie sehen diese Bibliothek/)).toBeInTheDocument()
      await create(user)

      await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
      const request = mockCreateNewLibrary.mock.calls[0][0]
      expect(request).toEqual(
        expect.objectContaining({
          name: 'Meine Ablage',
          sourceType: 'NEXTCLOUD',
          connectionProfileId: 'profile-intern',
          privateLibrary: true,
          ownerType: 'USER',
        }),
      )
      expect(request.sourceCredentials).toBeUndefined()
      expect(request.ownerId).toBeUndefined()
      expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
    }, 30000)

    it('tests the connection of a private library through the own account', async () => {
      mockListConnectionProfileOptions.mockResolvedValue([ownProfile])
      mockTestLibrarySource.mockResolvedValue({ reachable: true, message: 'Verbunden.' })
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)
      await user.click(await screen.findByRole('radio', { name: /Nextcloud intern · privat/ }))

      await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

      await waitFor(() => expect(mockTestLibrarySource).toHaveBeenCalled())
      const probe = mockTestLibrarySource.mock.calls[0][0]
      expect(probe).toEqual(
        expect.objectContaining({ connectionProfileId: 'profile-intern', privateLibrary: true }),
      )
      expect(probe.sourceCredentials).toBeUndefined()
    }, 20000)

    it('keeps the shareable way on a profile admitting both as an explicit choice of its own', async () => {
      mockListConnectionProfileOptions.mockResolvedValue([ownProfile])
      const user = userEvent.setup()
      await renderPage()
      await chooseType(user, /Nextcloud/)
      await screen.findByRole('radio', { name: /Nextcloud intern · privat/ })

      expect(screen.getByRole('radio', { name: /Nextcloud intern · privat/ })).toHaveAccessibleName(
        /später nicht änderbar/,
      )
      const shared = screen.getByRole('radio', { name: /^Nextcloud intern(?! · privat)/ })
      // the shareable way names its server, not the private one's note
      expect(shared).toHaveAccessibleName(/cloud\.intern\.example/)
      expect(shared).not.toHaveAccessibleName(/nur für Sie/)
      await user.click(shared)
      await user.type(screen.getByLabelText(/^Benutzername/), 'svc-opaa')
      await user.type(screen.getByLabelText(/^App-Passwort$/), 'geheim')
      expect(screen.getByText('4 · Freigaben')).toBeInTheDocument()
      await next(user)
      await nameItAndContinue(user, 'Projektablage')
      await create(user)

      await waitFor(() => expect(mockCreateNewLibrary).toHaveBeenCalled())
      const request = mockCreateNewLibrary.mock.calls[0][0]
      expect(request.connectionProfileId).toBe('profile-intern')
      expect(request.sourceCredentials).toBe('svc-opaa:geheim')
      expect(request).not.toHaveProperty('privateLibrary')
    }, 30000)
  })

  it('asks before discarding entered values on cancel', async () => {
    const user = userEvent.setup()
    await renderPage()

    await next(user)
    await user.type(screen.getByLabelText(/^Name/), 'R')
    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))
    await answerConfirm(user, 'Eingaben verwerfen und den Assistenten verlassen?', 'Abbrechen')

    expect(mockNavigate).not.toHaveBeenCalled()
  }, 15000)
})
