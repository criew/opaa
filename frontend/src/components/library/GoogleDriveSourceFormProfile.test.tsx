import { useState } from 'react'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import GoogleDriveSourceForm from './GoogleDriveSourceForm'
import { connectionFields, type SourceConnection } from './sources/sourceConnection'
import {
  EMPTY_GOOGLE_DRIVE_VALUES,
  type GoogleDriveSourceValues,
} from '../../utils/googleDriveSource'
import type { SourceConnectionTestRequest, SourceConnectionTestResponse } from '../../types/api'

const { mockTestLibrarySource } = vi.hoisted(() => ({
  mockTestLibrarySource:
    vi.fn<(request: SourceConnectionTestRequest) => Promise<SourceConnectionTestResponse>>(),
}))

vi.mock('../../services/libraryApi', async () => {
  const actual = await vi.importActual<typeof import('../../services/libraryApi')>(
    '../../services/libraryApi',
  )
  return { ...actual, testLibrarySource: mockTestLibrarySource }
})

function profile(defaults: Record<string, unknown>): SourceConnection {
  return {
    profileId: 'profile-drive',
    name: 'Zugang Drive',
    serverUrl: 'https://www.googleapis.com',
    authMethod: 'SERVICE_ACCOUNT_KEY',
    defaults,
  }
}

function Harness({
  connection,
  initial,
  onValues,
}: {
  connection: SourceConnection
  initial?: Partial<GoogleDriveSourceValues>
  onValues?: (values: GoogleDriveSourceValues) => void
}) {
  const [values, setValues] = useState<GoogleDriveSourceValues>({
    ...EMPTY_GOOGLE_DRIVE_VALUES,
    ...initial,
  })
  return (
    <GoogleDriveSourceForm
      mode="create"
      idPrefix="test-gd"
      values={values}
      connection={connectionFields({
        mode: 'create',
        sourceType: 'GOOGLE_DRIVE',
        idPrefix: 'test-gd',
        credentialsStored: false,
        connection,
      })}
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

/** Google Drive under a profile (#2220): the key and the imitated account are the profile's. */
describe('GoogleDriveSourceForm under a profile', () => {
  beforeEach(() => {
    mockTestLibrarySource.mockReset()
  })

  it('asks for neither key nor account and tests through the profile', async () => {
    const user = userEvent.setup()
    mockTestLibrarySource.mockResolvedValue({ reachable: true, message: 'ok', details: null })
    renderWithProviders(
      <Harness
        connection={profile({ subject: 'fach@example.org' })}
        initial={{
          subject: 'fach@example.org',
          scopes: [{ kind: 'drive', id: 'd1', name: 'Ablage' }],
        }}
      />,
    )

    expect(screen.queryByText('Dienstkonto-Schlüssel (JSON)')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Imitiertes Konto (optional)')).not.toBeInTheDocument()
    expect(screen.getByTestId('google-drive-profile-subject')).toHaveTextContent('fach@example.org')

    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await waitFor(() => expect(mockTestLibrarySource).toHaveBeenCalled())
    const request = mockTestLibrarySource.mock.calls[0][0]
    expect(request.connectionProfileId).toBe('profile-drive')
    expect(request.sourceCredentials).toBeUndefined()
    expect(request.sourceSettings).not.toHaveProperty('subject')
  })

  it('drops an own account the profile does not set', async () => {
    let latest: GoogleDriveSourceValues | undefined
    renderWithProviders(
      <Harness
        connection={profile({})}
        initial={{ subject: 'eigenes@example.org' }}
        onValues={(values) => {
          latest = values
        }}
      />,
    )

    expect(screen.getByTestId('google-drive-profile-subject')).toHaveTextContent(
      'imitiert kein Konto',
    )
    await waitFor(() => expect(latest?.subject).toBe(''))
  })
})
