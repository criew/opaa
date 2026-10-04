import { useState } from 'react'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import SmbSourceForm from './SmbSourceForm'
import { EMPTY_SMB_VALUES, type SmbSourceValues } from '../../utils/smbSource'
import type {
  SourceBrowseRequest,
  SourceBrowseResponse,
  SourceConnectionTestRequest,
  SourceConnectionTestResponse,
} from '../../types/api'

const { mockTestLibrarySource, mockBrowseSource } = vi.hoisted(() => ({
  mockTestLibrarySource:
    vi.fn<(request: SourceConnectionTestRequest) => Promise<SourceConnectionTestResponse>>(),
  mockBrowseSource:
    vi.fn<(sourceType: string, request: SourceBrowseRequest) => Promise<SourceBrowseResponse>>(),
}))

vi.mock('../../services/libraryApi', async () => {
  const actual = await vi.importActual<typeof import('../../services/libraryApi')>(
    '../../services/libraryApi',
  )
  return { ...actual, testLibrarySource: mockTestLibrarySource, browseSource: mockBrowseSource }
})

const STORED = 'smb://dateiserver.example.org/Daten'

function Harness({
  onValues,
  mode = 'create',
}: {
  onValues?: (values: SmbSourceValues) => void
  mode?: 'create' | 'edit'
}) {
  const [values, setValues] = useState<SmbSourceValues>({
    ...EMPTY_SMB_VALUES,
    sourceUrl: STORED,
    account: mode === 'create' ? 'RATHAUS\\svc-opaa' : '',
    password: mode === 'create' ? 'geheim' : '',
  })
  return (
    <SmbSourceForm
      mode={mode}
      idPrefix="test-smb"
      libraryId={mode === 'edit' ? 'lib-1' : undefined}
      credentialsStored={mode === 'edit'}
      originalSourceUrl={mode === 'edit' ? STORED : undefined}
      values={values}
      onChange={(patch) =>
        setValues((previous) => {
          const next = { ...previous, ...patch }
          onValues?.(next)
          return next
        })
      }
    />
  )
}

describe('SmbSourceForm', () => {
  beforeEach(() => {
    mockTestLibrarySource.mockReset()
    mockBrowseSource.mockReset()
  })

  it('offers the folders of the share root and replaces the whole share by the chosen ones', async () => {
    mockBrowseSource.mockResolvedValue({
      complete: true,
      entries: [
        { key: '/Bauamt', name: 'Bauamt' },
        { key: '/Hauptamt', name: 'Hauptamt' },
      ],
    })
    let latest: SmbSourceValues | undefined
    renderWithProviders(<Harness onValues={(values) => (latest = values)} />)

    await userEvent.click(screen.getByRole('button', { name: 'Ordner laden' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Ordner /Bauamt übernehmen' }))

    expect(mockBrowseSource).toHaveBeenCalledWith(
      'SMB',
      expect.objectContaining({
        sourceUrl: STORED,
        sourceCredentials: 'RATHAUS\\svc-opaa:geheim',
        sourceInsecureSsl: false,
      }),
    )
    expect(latest?.folders).toBe('/Bauamt')
  })

  it('shows why the share root cannot be listed', async () => {
    mockBrowseSource.mockResolvedValue({
      complete: false,
      entries: [],
      message: 'Das Dienstkonto darf den Stammordner der Freigabe nicht auflisten.',
    })
    renderWithProviders(<Harness />)

    await userEvent.click(screen.getByRole('button', { name: 'Ordner laden' }))

    expect(
      await screen.findByText('Das Dienstkonto darf den Stammordner der Freigabe nicht auflisten.'),
    ).toBeInTheDocument()
  })

  it('tests the connection with the folders and shows the finding', async () => {
    mockTestLibrarySource.mockResolvedValue({
      reachable: false,
      credentialsVerified: true,
      message: 'Angemeldet, aber: Die Freigabe „Daten“ gibt es auf dem Server nicht.',
    } as SourceConnectionTestResponse)
    renderWithProviders(<Harness />)

    await userEvent.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await waitFor(() =>
      expect(
        screen.getByText('Angemeldet, aber: Die Freigabe „Daten“ gibt es auf dem Server nicht.'),
      ).toBeInTheDocument(),
    )
    expect(mockTestLibrarySource).toHaveBeenCalledWith(
      expect.objectContaining({ sourceType: 'SMB', sourceSettings: { folders: ['/'] } }),
    )
  })

  it('hides a test result and the offered folders once the account changes', async () => {
    mockTestLibrarySource.mockResolvedValue({
      reachable: true,
      message: 'Verbindung hergestellt.',
    } as SourceConnectionTestResponse)
    mockBrowseSource.mockResolvedValue({
      complete: true,
      entries: [{ key: '/Bauamt', name: 'Bauamt' }],
    })
    renderWithProviders(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    await userEvent.click(screen.getByRole('button', { name: 'Ordner laden' }))
    await screen.findByText('Verbindung hergestellt.')
    await screen.findByRole('button', { name: 'Ordner /Bauamt übernehmen' })

    await userEvent.type(screen.getByLabelText('Dienstkonto'), '2')

    expect(screen.queryByText('Verbindung hergestellt.')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Ordner /Bauamt übernehmen' }),
    ).not.toBeInTheDocument()
  })

  it('hides a test result once the folders change, but keeps the offered folders', async () => {
    mockTestLibrarySource.mockResolvedValue({
      reachable: true,
      message: 'Verbindung hergestellt.',
    } as SourceConnectionTestResponse)
    mockBrowseSource.mockResolvedValue({
      complete: true,
      entries: [{ key: '/Bauamt', name: 'Bauamt' }],
    })
    renderWithProviders(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    await userEvent.click(screen.getByRole('button', { name: 'Ordner laden' }))
    await screen.findByText('Verbindung hergestellt.')

    await userEvent.type(screen.getByLabelText('Ordner'), '\n/Hauptamt')

    expect(screen.queryByText('Verbindung hergestellt.')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ordner /Bauamt übernehmen' })).toBeInTheDocument()
  })

  it('shows no answer that arrives after the values changed', async () => {
    let answer: (value: SourceConnectionTestResponse) => void = () => {}
    mockTestLibrarySource.mockReturnValue(
      new Promise((resolve) => {
        answer = resolve
      }),
    )
    renderWithProviders(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await userEvent.type(screen.getByLabelText('Passwort'), 'x')
    answer({ reachable: true, message: 'Verspätet.' } as SourceConnectionTestResponse)

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Verbindung testen' })).toBeEnabled(),
    )
    expect(screen.queryByText('Verspätet.')).not.toBeInTheDocument()
  })

  it('in edit mode tests through the library and keeps the credentials on the same server', async () => {
    mockTestLibrarySource.mockResolvedValue({
      reachable: true,
      message: 'Verbindung hergestellt.',
    } as SourceConnectionTestResponse)
    renderWithProviders(<Harness mode="edit" />)

    expect(
      screen.getByText('Leer lassen, um die gespeicherten Zugangsdaten beizubehalten.'),
    ).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    expect(mockTestLibrarySource).toHaveBeenCalledWith(
      expect.objectContaining({ libraryId: 'lib-1', sourceCredentials: undefined }),
    )
  })

  it('in edit mode warns about another server', async () => {
    renderWithProviders(<Harness mode="edit" />)
    const address = screen.getByLabelText('Adresse der Freigabe')

    await userEvent.clear(address)
    expect(screen.queryByText(/anderen Server/)).not.toBeInTheDocument()

    await userEvent.type(address, 'smb://anderer.example.org/Daten')
    expect(screen.getByText(/anderen Server/)).toBeInTheDocument()
  })

  it('names the offered folders as a group', async () => {
    mockBrowseSource.mockResolvedValue({
      complete: true,
      entries: [{ key: '/Projekte', name: 'Projekte' }],
    })
    renderWithProviders(<Harness />)

    await userEvent.click(screen.getByRole('button', { name: 'Ordner laden' }))

    const group = await screen.findByRole('group', { name: 'Gefundene Ordner' })
    expect(
      within(group).getByRole('button', { name: 'Ordner /Projekte übernehmen' }),
    ).toBeInTheDocument()
  })

  it('keeps the keyboard focus on the pressed button while the request runs', async () => {
    mockBrowseSource.mockReturnValue(new Promise(() => {}))
    mockTestLibrarySource.mockReturnValue(new Promise(() => {}))
    renderWithProviders(<Harness />)

    const load = screen.getByRole('button', { name: 'Ordner laden' })
    await userEvent.click(load)
    const loading = screen.getByRole('button', { name: 'Ordner werden geladen …' })
    expect(loading).toHaveFocus()
    expect(loading).toHaveAttribute('aria-disabled', 'true')
    expect(loading).toBeEnabled()
    expect(screen.getByText('Ordner werden geladen', { selector: '[aria-live]' })).toBeVisible()
    await userEvent.keyboard('{Enter}')
    expect(mockBrowseSource).toHaveBeenCalledTimes(1)

    const test = screen.getByRole('button', { name: 'Verbindung testen' })
    test.focus()
    await userEvent.keyboard('{Enter}')
    const testing = screen.getByRole('button', { name: 'Wird geprüft …' })
    expect(testing).toHaveFocus()
    await userEvent.click(testing)
    expect(mockTestLibrarySource).toHaveBeenCalledTimes(1)
  })
})
