import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import MetadataExtractionSettingsSection from './MetadataExtractionSettingsSection'

const { mockGetSettings, mockUpdateSettings, mockGetQuality } = vi.hoisted(() => ({
  mockGetSettings: vi.fn(),
  mockUpdateSettings: vi.fn(),
  mockGetQuality: vi.fn(),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
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

  it('names the permanent Abfluss and the chat role at the switch', async () => {
    renderWithProviders(
      <MetadataExtractionSettingsSection libraryId="library-team" canManage={true} />,
    )

    const hint = await screen.findByLabelText('Datenschutzhinweis')
    expect(hint).toHaveTextContent(
      'Mit eingeschalteter Extraktion verlässt der Inhalt jedes aufgenommenen Dokuments dauerhaft das Haus',
    )
    expect(hint).toHaveTextContent('https://api.openai.com/v1')
    expect(hint).toHaveTextContent('gpt-4o-mini')
  })

  it('says a locally operated model needs no outgoing connection', async () => {
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

    expect(await screen.findByLabelText('Datenschutzhinweis')).toHaveTextContent(
      'ohne ausgehende Verbindung',
    )
  })

  it('starts with both switches off and stores each change', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MetadataExtractionSettingsSection libraryId="library-team" canManage={true} />,
    )

    const modelSwitch = await screen.findByLabelText('Modellgestützte Extraktion', {
      selector: 'input',
    })
    const keywordSwitch = screen.getByLabelText('Freie Schlagworte', { selector: 'input' })
    expect(modelSwitch).not.toBeChecked()
    expect(keywordSwitch).not.toBeChecked()

    await user.click(modelSwitch)

    expect(mockUpdateSettings).toHaveBeenCalledWith('library-team', {
      modelExtractionEnabled: true,
      keywordsEnabled: false,
    })
    expect(
      await screen.findByLabelText('Modellgestützte Extraktion', { selector: 'input' }),
    ).toBeChecked()
  })

  it('shows the extraction quality per field and the Zählwerk', async () => {
    renderWithProviders(
      <MetadataExtractionSettingsSection libraryId="library-team" canManage={false} />,
    )

    expect(
      await screen.findByText(
        /regelbasiert 4 · modellbefüllt 3 · von Hand 1 · kein Wert ermittelbar 1 · leer 1 \(10 %\)/,
      ),
    ).toBeInTheDocument()
    expect(screen.getByText(/Modellaufrufe: 12/)).toBeInTheDocument()
    expect(screen.getByText(/verworfen \(Konfidenz\): 2/)).toBeInTheDocument()
    expect(screen.getByText(/nicht angefragt \(ausgelastet\): 2/)).toBeInTheDocument()
    // Without the management right the switches are not offered at all.
    expect(screen.queryByLabelText('Modellgestützte Extraktion', { selector: 'input' })).toBeNull()
    expect(mockGetSettings).not.toHaveBeenCalled()
  })
})
