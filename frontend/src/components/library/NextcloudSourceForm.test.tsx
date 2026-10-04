import { useState } from 'react'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import NextcloudSourceForm from './NextcloudSourceForm'
import { EMPTY_NEXTCLOUD_VALUES, type NextcloudSourceValues } from '../../utils/nextcloudSource'
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

function Harness({
  onValues,
  mode = 'create',
}: {
  onValues?: (values: NextcloudSourceValues) => void
  mode?: 'create' | 'edit'
}) {
  const [values, setValues] = useState<NextcloudSourceValues>({
    ...EMPTY_NEXTCLOUD_VALUES,
    sourceUrl: 'https://cloud.example.org',
    username: mode === 'create' ? 'opaa' : '',
    appPassword: mode === 'create' ? 'geheim' : '',
  })
  return (
    <NextcloudSourceForm
      mode={mode}
      idPrefix="test-nextcloud"
      libraryId={mode === 'edit' ? 'lib-1' : undefined}
      credentialsStored={mode === 'edit'}
      originalSourceUrl={mode === 'edit' ? 'https://cloud.example.org' : undefined}
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

describe('NextcloudSourceForm', () => {
  beforeEach(() => {
    mockTestLibrarySource.mockReset()
    mockBrowseSource.mockReset()
  })

  it('offers the folders of the user root and replaces the whole tree by the chosen ones', async () => {
    mockBrowseSource.mockResolvedValue({
      complete: true,
      entries: [
        { key: '/Projekte', name: 'Projekte' },
        { key: '/Bauamt', name: 'Bauamt (Gruppenordner)' },
      ],
    })
    let latest: NextcloudSourceValues | undefined
    renderWithProviders(<Harness onValues={(values) => (latest = values)} />)

    await userEvent.click(screen.getByRole('button', { name: 'Ordner laden' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Ordner /Bauamt übernehmen' }))

    expect(mockBrowseSource).toHaveBeenCalledWith(
      'NEXTCLOUD',
      expect.objectContaining({ sourceCredentials: 'opaa:geheim' }),
    )
    expect(latest?.folders).toBe('/Bauamt')
  })

  it('tests the connection with the folders and shows the answer', async () => {
    mockTestLibrarySource.mockResolvedValue({
      reachable: true,
      message: 'Verbindung hergestellt; 1 Ordner lesbar.',
    } as SourceConnectionTestResponse)
    renderWithProviders(<Harness />)

    await userEvent.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await waitFor(() =>
      expect(screen.getByText('Verbindung hergestellt; 1 Ordner lesbar.')).toBeInTheDocument(),
    )
    expect(mockTestLibrarySource).toHaveBeenCalledWith(
      expect.objectContaining({ sourceType: 'NEXTCLOUD', sourceSettings: { folders: ['/'] } }),
    )
  })

  it('hides a test result and the offered folders once the address changes', async () => {
    mockTestLibrarySource.mockResolvedValue({
      reachable: true,
      message: 'Verbindung hergestellt; 1 Ordner lesbar.',
    } as SourceConnectionTestResponse)
    mockBrowseSource.mockResolvedValue({
      complete: true,
      entries: [{ key: '/Projekte', name: 'Projekte' }],
    })
    renderWithProviders(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    await userEvent.click(screen.getByRole('button', { name: 'Ordner laden' }))
    await screen.findByText('Verbindung hergestellt; 1 Ordner lesbar.')
    await screen.findByRole('button', { name: 'Ordner /Projekte übernehmen' })

    await userEvent.type(screen.getByLabelText('Adresse der Nextcloud'), '/andere')

    expect(screen.queryByText('Verbindung hergestellt; 1 Ordner lesbar.')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Ordner /Projekte übernehmen' }),
    ).not.toBeInTheDocument()
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

    await userEvent.type(screen.getByLabelText('Ordner'), '\n/Projekte')
    answer({ reachable: true, message: 'Verspätet.' } as SourceConnectionTestResponse)

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Verbindung testen' })).toBeEnabled(),
    )
    expect(screen.queryByText('Verspätet.')).not.toBeInTheDocument()
  })

  it('in edit mode tests through the library with the stored credentials', async () => {
    mockTestLibrarySource.mockResolvedValue({
      reachable: true,
      message: 'Verbindung hergestellt; 1 Ordner lesbar.',
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

  it('in edit mode warns about another server only once an address is entered', async () => {
    renderWithProviders(<Harness mode="edit" />)
    const address = screen.getByLabelText('Adresse der Nextcloud')

    await userEvent.clear(address)
    expect(screen.queryByText(/anderen Server/)).not.toBeInTheDocument()

    await userEvent.type(address, 'https://andere.example.org')
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
    expect(screen.getByText('Ordner werden geladen', { selector: '[role="status"]' })).toBeVisible()
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
