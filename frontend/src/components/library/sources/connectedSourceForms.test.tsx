import { useState } from 'react'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import type {
  SourceConnectionTestRequest,
  SourceConnectionTestResponse,
  SourceTypeKey,
} from '../../../types/api'
import { sourceRegistration } from './registry'
import { withConnection, withoutFixed, type SourceConnection } from './sourceConnection'
import type { SourceFormContext } from './types'

const { mockTestLibrarySource } = vi.hoisted(() => ({
  mockTestLibrarySource:
    vi.fn<(request: SourceConnectionTestRequest) => Promise<SourceConnectionTestResponse>>(),
}))

vi.mock('../../../services/libraryApi', async () => {
  const actual = await vi.importActual<typeof import('../../../services/libraryApi')>(
    '../../../services/libraryApi',
  )
  return { ...actual, testLibrarySource: mockTestLibrarySource }
})

/** Renders a registered form the way the wizard does: under a profile, fixed fields kept. */
function Connected({
  sourceType,
  connection,
  initial,
}: {
  sourceType: SourceTypeKey
  connection: SourceConnection
  initial?: Record<string, unknown>
}) {
  const configuration = sourceRegistration(sourceType)!.configuration!
  const [values, setValues] = useState<unknown>({
    ...(configuration.empty as object),
    ...initial,
  })
  const shown = withConnection(values, connection)
  const context: SourceFormContext = {
    mode: 'create',
    sourceType,
    idPrefix: 'test',
    credentialsStored: false,
    connection,
  }
  const Form = configuration.Form
  return (
    <Form
      values={shown}
      context={context}
      onChange={(patch: object) =>
        setValues({ ...(shown as object), ...withoutFixed(patch, connection) })
      }
    />
  )
}

describe('source forms under a connection profile', () => {
  beforeEach(() => {
    mockTestLibrarySource.mockReset()
    mockTestLibrarySource.mockResolvedValue({ reachable: true, message: 'erreichbar' })
  })

  it('prefills the address, drops the secret field for a profile without sign-in and tests through the profile', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Connected
        sourceType="HTTP_DIRECTORY"
        connection={{
          profileId: 'profile-intranet',
          name: 'Intranet',
          serverUrl: 'https://intranet.example',
          authMethod: 'NONE',
          defaults: {},
        }}
      />,
    )

    expect(screen.getByLabelText(/Adresse \(URL\)/)).toHaveValue('https://intranet.example')
    expect(screen.getByText(/Server-Adresse des Zugangs „Intranet“/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/Anmeldedaten/)).not.toBeInTheDocument()

    await user.type(screen.getByLabelText(/Adresse \(URL\)/), '/handbuch/')
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await waitFor(() =>
      expect(mockTestLibrarySource).toHaveBeenCalledWith(
        expect.objectContaining({
          sourceType: 'HTTP_DIRECTORY',
          sourceUrl: 'https://intranet.example/handbuch/',
          connectionProfileId: 'profile-intranet',
        }),
      ),
    )
    expect(mockTestLibrarySource.mock.calls[0][0]).not.toHaveProperty('libraryId')
  })

  it('keeps the secret field for a profile whose sign-in takes the library’s own secret', () => {
    renderWithProviders(
      <Connected
        sourceType="RSS_FEED"
        connection={{
          profileId: 'profile-feed',
          name: 'Pressestelle',
          serverUrl: 'https://presse.example',
          authMethod: 'PERSONAL_SECRET',
          defaults: {},
        }}
      />,
    )

    expect(screen.getByLabelText(/Anmeldedaten/)).toBeInTheDocument()
  })

  it('shows the settings a profile fixes read-only with its value, and no provider template', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Connected
        sourceType="S3"
        connection={{
          profileId: 'profile-s3',
          name: 'Speicher Rechenzentrum',
          serverUrl: 'https://s3.rz.example',
          authMethod: 'PERSONAL_SECRET',
          defaults: { region: 'eu-rz-1', pathStyle: true },
        }}
      />,
    )

    expect(screen.getByLabelText('Endpoint')).toHaveValue('https://s3.rz.example')
    const region = screen.getByLabelText(/^Region/)
    expect(region).toHaveValue('eu-rz-1')
    expect(region).toHaveAttribute('readonly')
    expect(screen.getByRole('switch', { name: /Path-Style/ })).toBeChecked()
    expect(screen.getByRole('switch', { name: /Path-Style/ })).toBeDisabled()
    expect(screen.getAllByText('Vom Zugang „Speicher Rechenzentrum“ vorgegeben.')).toHaveLength(2)
    expect(screen.queryByRole('radiogroup', { name: 'Anbieter' })).not.toBeInTheDocument()

    await user.type(region, 'x')
    expect(region).toHaveValue('eu-rz-1')
    expect(screen.getByLabelText('Access Key')).toBeInTheDocument()
  })

  it('takes the edition a profile fixes instead of detecting it', () => {
    renderWithProviders(
      <Connected
        sourceType="CONFLUENCE"
        connection={{
          profileId: 'profile-wiki',
          name: 'Wiki intern',
          serverUrl: 'https://wiki.example',
          authMethod: 'PERSONAL_SECRET',
          defaults: { edition: 'DATA_CENTER' },
        }}
      />,
    )

    expect(screen.queryByRole('button', { name: 'Edition erkennen' })).not.toBeInTheDocument()
    expect(screen.getByTestId('test-confluence-edition')).toHaveTextContent('Data Center')
    expect(screen.getByText('Vom Zugang „Wiki intern“ vorgegeben.')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Personal Access Token/)).toBeInTheDocument()
  })

  it('keeps „Verbindung testen“ usable for a profile without a secret of the library', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Connected
        sourceType="CONFLUENCE"
        connection={{
          profileId: 'profile-wiki',
          name: 'Wiki öffentlich',
          serverUrl: 'https://wiki.example',
          authMethod: 'NONE',
          defaults: { edition: 'DATA_CENTER' },
        }}
      />,
    )

    expect(screen.queryByLabelText(/Personal Access Token/)).not.toBeInTheDocument()
    const test = screen.getByRole('button', { name: 'Verbindung testen' })
    expect(test).toBeEnabled()
    await user.click(test)
    await waitFor(() =>
      expect(mockTestLibrarySource).toHaveBeenCalledWith(
        expect.objectContaining({ connectionProfileId: 'profile-wiki' }),
      ),
    )
  })

  it.each([
    [
      'NEXTCLOUD' as SourceTypeKey,
      'https://cloud.example',
      /^Adresse der Nextcloud/,
      [/^Technischer Nutzer/, /^App-Passwort/],
    ],
    [
      'SMB' as SourceTypeKey,
      'smb://dateiserver.example',
      /^Adresse der Freigabe/,
      [/^Dienstkonto/, /^Passwort/],
    ],
  ])(
    'prefills the %s address from the profile and asks for the library’s own secret',
    (sourceType, serverUrl, addressLabel, secretLabels) => {
      renderWithProviders(
        <Connected
          sourceType={sourceType}
          connection={{
            profileId: 'profile-files',
            name: 'Ablage',
            serverUrl,
            authMethod: 'PERSONAL_SECRET',
            defaults: {},
          }}
        />,
      )

      expect(screen.getByLabelText(addressLabel)).toHaveValue(serverUrl)
      expect(screen.getByText(/Server-Adresse des Zugangs „Ablage“/)).toBeInTheDocument()
      for (const label of secretLabels) {
        expect(screen.getByLabelText(label)).toBeInTheDocument()
      }
    },
  )

  it('derives no endpoint from the region under a profile, even with a provider template behind it', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Connected
        sourceType="S3"
        initial={{ provider: 'AWS', region: '' }}
        connection={{
          profileId: 'profile-s3',
          name: 'Speicher Rechenzentrum',
          serverUrl: 'https://s3.rz.example',
          authMethod: 'PERSONAL_SECRET',
          defaults: {},
        }}
      />,
    )

    await user.type(screen.getByLabelText(/^Region/), 'eu-central-1')
    expect(screen.getByLabelText(/^Region/)).toHaveValue('eu-central-1')
    expect(screen.getByLabelText('Endpoint')).toHaveValue('https://s3.rz.example')
  })
})
