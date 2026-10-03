import { useState } from 'react'
import { screen, waitFor } from '@testing-library/react'
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

function Harness({ onValues }: { onValues?: (values: NextcloudSourceValues) => void }) {
  const [values, setValues] = useState<NextcloudSourceValues>({
    ...EMPTY_NEXTCLOUD_VALUES,
    sourceUrl: 'https://cloud.example.org',
    username: 'opaa',
    appPassword: 'geheim',
  })
  return (
    <NextcloudSourceForm
      mode="create"
      idPrefix="test-nextcloud"
      credentialsStored={false}
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
})
