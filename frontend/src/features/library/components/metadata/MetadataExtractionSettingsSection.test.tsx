import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  answerConfirm,
  renderWithProviders,
  waitForDialogClosed,
} from '../../../../test/test-utils'
import MetadataExtractionSettingsSection, {
  MetadataFillSection,
} from './MetadataExtractionSettingsSection'

const { mockGetSettings, mockUpdateSettings, mockGetQuality } = vi.hoisted(() => ({
  mockGetSettings: vi.fn(),
  mockUpdateSettings: vi.fn(),
  mockGetQuality: vi.fn(),
}))

vi.mock('../../api/libraryMetadataApi', async () => {
  const actual = await vi.importActual<typeof import('../../api/libraryMetadataApi')>(
    '../../api/libraryMetadataApi',
  )
  return {
    ...actual,
    getLibraryMetadataExtractionSettings: mockGetSettings,
    updateLibraryMetadataExtractionSettings: mockUpdateSettings,
    getLibraryMetadataQuality: mockGetQuality,
  }
})

const remoteSettings = {
  libraryId: 'library-team',
  modelExtractionEnabled: false,
  keywordsEnabled: false,
  confidenceThreshold: 0.8,
  chatModel: {
    baseUrl: 'https://api.openai.com/v1',
    modelIdentifier: 'gpt-4o-mini',
    local: false,
  },
}

const quality = {
  libraryId: 'library-team',
  totalDocuments: 10,
  modelExtractionEnabled: false,
  keywordsEnabled: false,
  confidenceThreshold: 0.8,
  fields: [
    {
      fieldKey: 'document_type',
      label: 'Dokumentart',
      totalDocuments: 10,
      deterministicDocuments: 4,
      derivedDocuments: 3,
      manualDocuments: 1,
      notDeterminableDocuments: 1,
      emptyDocuments: 1,
      derivedShare: 0.3,
      emptyShare: 0.1,
    },
  ],
  modelExtraction: {
    calls: 12,
    acceptedValues: 8,
    rejectedBelowThreshold: 2,
    rejectedOutsideVocabulary: 1,
    failures: 1,
    rejectedPoolFull: 2,
    keywordsAssigned: 20,
    lastCallAt: '2026-09-01T06:05:00Z',
  },
}

describe('MetadataExtractionSettingsSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetSettings.mockResolvedValue(remoteSettings)
    mockGetQuality.mockResolvedValue(quality)
    mockUpdateSettings.mockImplementation((_libraryId: string, request: unknown) =>
      Promise.resolve({ ...remoteSettings, ...(request as Record<string, unknown>) }),
    )
  })

  async function openSwitches(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: /Ändern\s*Extraktion/ }))
  }

  it('names the permanent Abfluss to the remote provider at the switch', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MetadataExtractionSettingsSection libraryId="library-team" canManage={true} />,
    )
    await openSwitches(user)

    await user.click(screen.getByLabelText('Modellgestützte Extraktion', { selector: 'input' }))

    const dialog = await screen.findByRole('dialog', { name: 'Extraktion einschalten?' })
    expect(dialog).toHaveTextContent(
      'Der Inhalt jedes neuen Dokuments geht dann an api.openai.com.',
    )
    // Nothing leaves the house before the question is answered.
    expect(mockUpdateSettings).not.toHaveBeenCalled()

    await answerConfirm(user, 'Extraktion einschalten?', 'Abbrechen')
    await waitForDialogClosed()
    expect(mockUpdateSettings).not.toHaveBeenCalled()
    expect(
      screen.getByLabelText('Modellgestützte Extraktion', { selector: 'input' }),
    ).not.toBeChecked()
  })

  it('switches a locally operated model on without asking about an outgoing connection', async () => {
    const user = userEvent.setup()
    mockGetSettings.mockResolvedValue({
      ...remoteSettings,
      chatModel: {
        baseUrl: 'http://localhost:11434/v1',
        modelIdentifier: 'qwen3:8b',
        local: true,
      },
    })

    renderWithProviders(
      <MetadataExtractionSettingsSection libraryId="library-team" canManage={true} />,
    )
    await openSwitches(user)

    await user.click(screen.getByLabelText('Freie Schlagworte', { selector: 'input' }))

    expect(mockUpdateSettings).toHaveBeenCalledWith('library-team', {
      modelExtractionEnabled: false,
      keywordsEnabled: true,
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('starts with both switches off and stores each change', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MetadataExtractionSettingsSection libraryId="library-team" canManage={true} />,
    )

    expect(
      await screen.findByText('Modellgestützt aus · Freie Schlagworte aus'),
    ).toBeInTheDocument()
    await openSwitches(user)
    const modelSwitch = screen.getByLabelText('Modellgestützte Extraktion', { selector: 'input' })
    const keywordSwitch = screen.getByLabelText('Freie Schlagworte', { selector: 'input' })
    expect(modelSwitch).not.toBeChecked()
    expect(keywordSwitch).not.toBeChecked()

    await user.click(modelSwitch)
    await answerConfirm(user, 'Extraktion einschalten?', 'Einschalten')

    await waitFor(() =>
      expect(mockUpdateSettings).toHaveBeenCalledWith('library-team', {
        modelExtractionEnabled: true,
        keywordsEnabled: false,
      }),
    )
    expect(
      await screen.findByLabelText('Modellgestützte Extraktion', { selector: 'input' }),
    ).toBeChecked()
    expect(screen.getByText('Modellgestützt an · Freie Schlagworte aus')).toBeInTheDocument()
  })

  it('shows the model and the Zählwerk under the technical details', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MetadataExtractionSettingsSection libraryId="library-team" canManage={true} />,
    )
    await openSwitches(user)

    await user.click(await screen.findByRole('button', { name: 'Technische Angaben' }))

    const details = screen.getByText(/Modellaufrufe 12/)
    expect(details).toHaveTextContent('Modell gpt-4o-mini über https://api.openai.com/v1')
    expect(details).toHaveTextContent('verworfen (Konfidenz) 2')
    expect(details).toHaveTextContent('nicht angefragt 2')
    expect(details).toHaveTextContent('Schlagworte 20')
  })

  it('offers no switches without the management right', async () => {
    renderWithProviders(
      <MetadataExtractionSettingsSection libraryId="library-team" canManage={false} />,
    )

    expect(await screen.findByRole('heading', { name: 'Extraktion' })).toBeInTheDocument()
    await waitFor(() => expect(mockGetQuality).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: /Ändern/ })).toBeNull()
    expect(screen.queryByLabelText('Modellgestützte Extraktion', { selector: 'input' })).toBeNull()
    expect(mockGetSettings).not.toHaveBeenCalled()
  })
})

describe('MetadataFillSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetQuality.mockResolvedValue(quality)
  })

  it('shows the fill level per field with the origin of its values', async () => {
    renderWithProviders(<MetadataFillSection libraryId="library-team" />, { withRouter: true })

    const row = (await screen.findByText('9 von 10 gefüllt (90 %)')).closest('li')
    expect(row).toHaveTextContent('Dokumentart')
    expect(row).toHaveAttribute(
      'title',
      'regelbasiert 4 · modellbefüllt 3 · von Hand 1 · nicht ermittelbar 1',
    )
    expect(
      screen.getByRole('button', { name: /Anzeigen.*Dokumente ohne Dokumentart/ }),
    ).toBeInTheDocument()
  })

  it('shows the documents without a value of a field through „Anzeigen“', async () => {
    const user = userEvent.setup()
    function Search() {
      return <output aria-label="Adresse">{useLocation().search}</output>
    }
    renderWithProviders(
      <>
        <MetadataFillSection libraryId="library-team" />
        <Search />
      </>,
      { withRouter: true, initialRoute: '/libraries/library-team?tab=metadaten' },
    )

    await user.click(
      await screen.findByRole('button', { name: /Anzeigen\s*Dokumente ohne Dokumentart/ }),
    )

    // the filter replaces the tab, so the jump lands in the documents, filtered to the empty ones
    expect(screen.getByRole('status', { name: 'Adresse' })).toHaveTextContent(
      /^\?missingField=document_type$/,
    )
  })
})
