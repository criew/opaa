import { useState } from 'react'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import GoogleDriveSourceForm from './GoogleDriveSourceForm'
import {
  EMPTY_GOOGLE_DRIVE_VALUES,
  type GoogleDriveSourceValues,
} from '../../utils/googleDriveSource'
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

const KEY_FILE = JSON.stringify({
  type: 'service_account',
  client_email: 'leser@projekt.iam.gserviceaccount.com',
  private_key_id: 'abc',
  private_key: '-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n',
})

function Harness({
  initial,
  mode = 'create',
  credentialsStored,
  onValues,
}: {
  initial?: Partial<GoogleDriveSourceValues>
  mode?: 'create' | 'edit'
  credentialsStored?: boolean
  onValues?: (values: GoogleDriveSourceValues) => void
}) {
  const [values, setValues] = useState<GoogleDriveSourceValues>({
    ...EMPTY_GOOGLE_DRIVE_VALUES,
    ...initial,
  })
  return (
    <GoogleDriveSourceForm
      mode={mode}
      idPrefix="test-gd"
      values={values}
      libraryId={mode === 'edit' ? 'lib-1' : undefined}
      credentialsStored={credentialsStored}
      onChange={(patch) =>
        setValues((prev) => {
          const next = { ...prev, ...patch }
          onValues?.(next)
          return next
        })
      }
    />
  )
}

describe('GoogleDriveSourceForm (ADR-0040)', () => {
  beforeEach(() => {
    mockTestLibrarySource.mockReset()
    mockBrowseSource.mockReset()
  })

  it('reads the key file only to name its account and keeps the file for the request', async () => {
    const user = userEvent.setup()
    let latest: GoogleDriveSourceValues | undefined
    renderWithProviders(<Harness onValues={(values) => (latest = values)} />)

    await user.upload(
      screen.getByLabelText('Schlüsseldatei des Dienstkontos'),
      new File([KEY_FILE], 'schluessel.json', { type: 'application/json' }),
    )

    expect(await screen.findByTestId('google-drive-key-status')).toHaveTextContent(
      'leser@projekt.iam.gserviceaccount.com',
    )
    expect(latest?.keyFile).toBe(KEY_FILE)
    expect(screen.queryByText(/BEGIN PRIVATE KEY/)).not.toBeInTheDocument()
  })

  it('refuses a file that is no service account key', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.upload(
      screen.getByLabelText('Schlüsseldatei des Dienstkontos'),
      new File(['{"kein":"schlüssel"}'], 'falsch.json', { type: 'application/json' }),
    )

    expect(await screen.findByText(/keine JSON-Schlüsseldatei/)).toBeInTheDocument()
  })

  it('lists the areas the account sees and adds the chosen one', async () => {
    const user = userEvent.setup()
    let latest: GoogleDriveSourceValues | undefined
    mockBrowseSource.mockResolvedValue({
      complete: true,
      entries: [
        { key: 'drive:d1', name: 'Projektablage' },
        { key: 'folder:f1', name: 'Freigabe' },
      ],
    })
    renderWithProviders(
      <Harness initial={{ keyFile: KEY_FILE }} onValues={(values) => (latest = values)} />,
    )

    await user.click(screen.getByRole('button', { name: 'Bereiche laden' }))
    await user.click(await screen.findByLabelText('Projektablage (Geteilte Ablage)'))

    expect(mockBrowseSource).toHaveBeenCalledWith(
      'GOOGLE_DRIVE',
      expect.objectContaining({
        sourceUrl: 'https://www.googleapis.com',
        sourceCredentials: KEY_FILE,
      }),
    )
    expect(latest?.scopes).toEqual([{ kind: 'drive', id: 'd1', name: 'Projektablage' }])
  })

  it('asks for the key again when the imitated account of a stored key changes', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Harness
        mode="edit"
        credentialsStored
        initial={{ subject: 'a@example.org', storedSubject: 'a@example.org' }}
      />,
    )
    expect(screen.queryByTestId('google-drive-subject-needs-key')).not.toBeInTheDocument()

    const subject = screen.getByLabelText('Imitiertes Konto (optional)')
    await user.clear(subject)
    await user.type(subject, 'b@example.org')

    expect(screen.getByTestId('google-drive-subject-needs-key')).toBeInTheDocument()
  })

  it('reports the connection test per area', async () => {
    const user = userEvent.setup()
    mockTestLibrarySource.mockResolvedValue({
      reachable: false,
      message: '1 von 2 Bereichen sind für das Konto nicht sichtbar.',
      details: {
        scopes: [
          { scope: 'drive:d1', reachable: true },
          { scope: 'folder:f1', reachable: false, message: 'Der Ordner ist nicht sichtbar.' },
        ],
      },
    })
    renderWithProviders(
      <Harness
        initial={{
          keyFile: KEY_FILE,
          scopes: [
            { kind: 'drive', id: 'd1', name: 'Projektablage' },
            { kind: 'folder', id: 'f1', name: 'Freigabe' },
          ],
        }}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await waitFor(() =>
      expect(screen.getByTestId('google-drive-test-status')).toHaveTextContent('1 von 2 Bereichen'),
    )
    expect(
      screen.getByText(/Freigabe · Ordner · Der Ordner ist nicht sichtbar/),
    ).toBeInTheDocument()
    expect(mockTestLibrarySource).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceType: 'GOOGLE_DRIVE',
        sourceSettings: {
          scopes: [
            { drive: 'd1', name: 'Projektablage' },
            { folder: 'f1', name: 'Freigabe' },
          ],
          subject: null,
        },
      }),
    )
  })

  it('frees the test button when a field changes while the test is running', async () => {
    const user = userEvent.setup()
    let resolve: (value: SourceConnectionTestResponse) => void = () => {}
    mockTestLibrarySource.mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    renderWithProviders(
      <Harness
        initial={{ keyFile: KEY_FILE, scopes: [{ kind: 'folder', id: 'f1', name: 'Freigabe' }] }}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    expect(screen.getByRole('button', { name: 'Prüft …' })).toHaveAttribute('aria-disabled', 'true')
    await user.type(screen.getByLabelText('Proxy (optional)'), 'p')
    resolve({ reachable: true, message: 'alt', details: null })

    expect(await screen.findByRole('button', { name: 'Verbindung testen' })).toBeEnabled()
    expect(screen.getByTestId('google-drive-test-status')).not.toHaveTextContent('alt')
  })

  it('drops the listing of the old account when key or account change', async () => {
    const user = userEvent.setup()
    mockBrowseSource.mockResolvedValue({
      complete: true,
      entries: [{ key: 'drive:d1', name: 'Projektablage' }],
    })
    renderWithProviders(<Harness initial={{ keyFile: KEY_FILE }} />)

    await user.click(screen.getByRole('button', { name: 'Bereiche laden' }))
    expect(await screen.findByLabelText('Projektablage (Geteilte Ablage)')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Imitiertes Konto (optional)'), 'x@example.org')

    expect(screen.queryByLabelText('Projektablage (Geteilte Ablage)')).not.toBeInTheDocument()
  })

  it('keeps the status region in the DOM before any test', () => {
    renderWithProviders(<Harness />)

    expect(screen.getByTestId('google-drive-test-status')).toBeEmptyDOMElement()
  })
})
