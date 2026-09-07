import { useState } from 'react'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import S3SourceForm from './S3SourceForm'
import { EMPTY_S3_VALUES, type S3SourceValues } from '../../utils/s3Source'
import type {
  S3BucketListRequest,
  S3BucketListResponse,
  SourceConnectionTestRequest,
  SourceConnectionTestResponse,
} from '../../types/api'

const { mockTestLibrarySource, mockListS3Buckets } = vi.hoisted(() => ({
  mockTestLibrarySource:
    vi.fn<(request: SourceConnectionTestRequest) => Promise<SourceConnectionTestResponse>>(),
  mockListS3Buckets: vi.fn<(request: S3BucketListRequest) => Promise<S3BucketListResponse>>(),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    testLibrarySource: mockTestLibrarySource,
    listS3Buckets: mockListS3Buckets,
  }
})

/** The form is controlled; this harness owns the values like the wizard does. */
function Harness({
  initial,
  mode = 'create',
  onValues,
  libraryId,
  credentialsStored,
  originalSourceUrl,
}: {
  initial?: Partial<S3SourceValues>
  mode?: 'create' | 'edit'
  onValues?: (values: S3SourceValues) => void
  libraryId?: string
  credentialsStored?: boolean
  originalSourceUrl?: string | null
}) {
  const [values, setValues] = useState<S3SourceValues>({ ...EMPTY_S3_VALUES, ...initial })
  return (
    <S3SourceForm
      mode={mode}
      idPrefix="test-s3"
      values={values}
      libraryId={libraryId}
      credentialsStored={credentialsStored}
      originalSourceUrl={originalSourceUrl}
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

const keyed: Partial<S3SourceValues> = {
  sourceUrl: 'https://minio.intern.example:9000',
  accessKey: 'AKIAEXAMPLE',
  secretKey: 'geheim',
  scopes: [{ bucket: 'protokolle', prefix: '2025/' }],
}

describe('S3SourceForm (#1377, ADR-0027)', () => {
  beforeEach(() => {
    mockTestLibrarySource.mockReset()
    mockListS3Buckets.mockReset()
  })

  it('states the sharing consequence before the scope list and derives the endpoint from the template', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    const consequence = screen.getByTestId('test-s3-sharing-consequence')
    const scopes = screen.getByRole('list', { name: 'Geltungsbereiche' })
    expect(consequence).toHaveTextContent('sieht alles aus allen Geltungsbereichen')
    expect(
      consequence.compareDocumentPosition(scopes) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()

    // MinIO is the default template: path-style, no endpoint
    expect(screen.getByLabelText('Endpoint')).toHaveValue('')
    expect(screen.getByLabelText(/Path-Style/)).toBeChecked()

    await user.click(screen.getByRole('radio', { name: 'AWS S3' }))
    expect(screen.getByLabelText('Endpoint')).toHaveValue('https://s3.eu-central-1.amazonaws.com')
    expect(screen.getByLabelText(/Path-Style/)).not.toBeChecked()
    expect(screen.getByLabelText('Region')).toHaveValue('eu-central-1')

    await user.click(screen.getByRole('radio', { name: 'Hetzner Object Storage' }))
    expect(screen.getByLabelText('Endpoint')).toHaveValue('https://fsn1.your-objectstorage.com')
    expect(screen.getByText(/keine Ereignisbenachrichtigungen/)).toBeInTheDocument()
  })

  it('adds and removes scopes with a labelled control, never below one row, and keeps the focus', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    expect(screen.getByRole('button', { name: 'Bereich 1 entfernen' })).toBeDisabled()
    // the test needs a scope before it can run at all
    expect(screen.getByRole('button', { name: 'Verbindung testen' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Bereich' }))
    expect(screen.getByLabelText('Bucket 2')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('2 von höchstens 50 Bereichen.')
    await user.click(screen.getByRole('button', { name: 'Bereich' }))
    await user.click(screen.getByRole('button', { name: 'Bereich 3 entfernen' }))
    expect(screen.getByRole('button', { name: 'Bereich 2 entfernen' })).toHaveFocus()
    await user.click(screen.getByRole('button', { name: 'Bereich 2 entfernen' }))
    expect(screen.queryByLabelText('Bucket 2')).not.toBeInTheDocument()
    // the previous row's button is disabled as the only one left - the focus goes to "Bereich"
    expect(screen.getByRole('button', { name: 'Bereich' })).toHaveFocus()
    // a prefix problem is reported at the prefix field, a bucket problem at the bucket field
    await user.type(screen.getByLabelText('Bucket 1'), 'Gross')
    expect(screen.getByLabelText('Bucket 1')).toHaveAccessibleDescription(/Bucket-Name „Gross“/)
  })

  it('does not drop a bucket listing in flight when a scope row is edited meanwhile', async () => {
    const user = userEvent.setup()
    let resolveListing: (value: S3BucketListResponse) => void = () => {}
    mockListS3Buckets.mockImplementationOnce(
      () => new Promise<S3BucketListResponse>((resolve) => (resolveListing = resolve)),
    )
    renderWithProviders(<Harness initial={keyed} />)

    await user.click(screen.getByRole('button', { name: 'Buckets laden' }))
    await user.type(screen.getByLabelText(/^Präfix 1/), 'x')
    resolveListing({ listingPermitted: true, buckets: ['protokolle'], message: null })
    expect(await screen.findByText(/1 Bucket geladen/)).toBeInTheDocument()
  })

  it('loads the buckets and falls back to manual entry without an error state when not permitted', async () => {
    const user = userEvent.setup()
    mockListS3Buckets.mockResolvedValueOnce({
      listingPermitted: false,
      buckets: [],
      message: 'Die Bucket-Liste ist mit diesen Zugangsdaten nicht lesbar - von Hand eintragen.',
    })
    renderWithProviders(<Harness initial={keyed} />)

    await user.click(screen.getByRole('button', { name: 'Buckets laden' }))
    expect(await screen.findByText(/von Hand eintragen/)).toBeInTheDocument()
    expect(screen.getByText(/von Hand eintragen/).closest('.MuiAlert-root')).not.toHaveClass(
      'MuiAlert-standardError',
    )
    // manual entry still works
    const bucket = screen.getByLabelText('Bucket 1')
    await user.clear(bucket)
    await user.type(bucket, 'satzungen')
    expect(bucket).toHaveValue('satzungen')
    expect(mockListS3Buckets).toHaveBeenCalledWith({
      sourceUrl: 'https://minio.intern.example:9000',
      sourceProxy: undefined,
      sourceInsecureSsl: false,
      sourceCredentials: 'AKIAEXAMPLE:geheim',
      libraryId: undefined,
      region: 'us-east-1',
      pathStyle: true,
    })

    mockListS3Buckets.mockResolvedValueOnce({
      listingPermitted: true,
      buckets: ['protokolle', 'satzungen'],
      message: null,
    })
    await user.click(screen.getByRole('button', { name: 'Buckets laden' }))
    expect(await screen.findByText(/2 Buckets geladen/)).toBeInTheDocument()
  })

  it('tests the connection with the settings and shows the finding of every scope', async () => {
    const user = userEvent.setup()
    mockTestLibrarySource.mockResolvedValueOnce({
      reachable: false,
      credentialsVerified: true,
      message: 'Bereich „archiv“: s3:GetObject fehlt',
      s3Scopes: [
        {
          bucket: 'protokolle',
          prefix: '2025/',
          bucketReachable: true,
          listAllowed: true,
          readAllowed: true,
          objectCount: 412,
          objectCountIsLowerBound: false,
          message: null,
        },
        {
          bucket: 'archiv',
          prefix: '',
          bucketReachable: true,
          listAllowed: true,
          readAllowed: false,
          objectCount: 1000,
          objectCountIsLowerBound: true,
          message: 'darf nicht gelesen werden (s3:GetObject fehlt).',
        },
      ],
    })
    renderWithProviders(
      <Harness
        initial={{
          ...keyed,
          scopes: [
            { bucket: 'protokolle', prefix: '2025' },
            { bucket: 'archiv', prefix: '' },
          ],
        }}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    expect(mockTestLibrarySource).toHaveBeenCalledWith({
      sourceType: 'S3',
      sourceUrl: 'https://minio.intern.example:9000',
      sourceProxy: undefined,
      sourceInsecureSsl: false,
      sourceCredentials: 'AKIAEXAMPLE:geheim',
      libraryId: undefined,
      s3Settings: {
        region: 'us-east-1',
        pathStyle: true,
        scopes: [
          { bucket: 'protokolle', prefix: '2025/' },
          { bucket: 'archiv', prefix: null },
        ],
        includePatterns: [],
        excludePatterns: [],
      },
    })
    const checks = await screen.findByTestId('test-s3-scope-checks')
    expect(checks).toHaveTextContent(
      '✓ protokolle/2025/: erreichbar · Listen ✓ · Lesen ✓ · 412 Objekte',
    )
    expect(checks).toHaveTextContent('✗ archiv: darf nicht gelesen werden (s3:GetObject fehlt).')
    expect(screen.getByText('Bereich „archiv“: s3:GetObject fehlt')).toBeInTheDocument()

    // editing the key withdraws the result
    await user.type(screen.getByLabelText('Access Key'), 'X')
    await waitFor(() =>
      expect(screen.queryByTestId('test-s3-scope-checks')).not.toBeInTheDocument(),
    )
  })

  it('is fully operable by keyboard in reading order (docs/design/accessibility.md §3.2)', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness initial={keyed} />)

    // the tab order follows the stages: provider, region, endpoint, addressing style, key, scopes,
    // scope actions, the advanced group, the test - every stop has an accessible name
    const expected: Array<() => HTMLElement> = [
      () => screen.getByRole('radio', { name: 'MinIO / S3-kompatibel' }),
      () => screen.getByLabelText('Region'),
      () => screen.getByLabelText('Endpoint'),
      () => screen.getByLabelText(/Path-Style/),
      () => screen.getByLabelText('Access Key'),
      () => screen.getByLabelText('Secret Key'),
      () => screen.getByLabelText('Session-Token (optional)'),
      () => screen.getByLabelText('Bucket 1'),
      () => screen.getByLabelText('Präfix 1 (optional)'),
      () => screen.getByRole('button', { name: 'Bereich' }),
      () => screen.getByRole('button', { name: 'Buckets laden' }),
      () => screen.getByRole('button', { name: /Erweitert/ }),
      () => screen.getByRole('button', { name: 'Verbindung testen' }),
    ]
    for (const stop of expected) {
      await user.tab()
      expect(stop()).toHaveFocus()
    }
    // the disabled remove button of the only row is skipped, the advanced group opens on Enter
    await user.tab({ shift: true })
    await user.keyboard('{Enter}')
    expect(screen.getByRole('button', { name: /Erweitert/ })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    await user.tab()
    expect(screen.getByRole('textbox', { name: 'Einschlussmuster (optional)' })).toHaveFocus()
  })

  it('in edit mode keeps the stored key, tests through the library and drops it on a host change', async () => {
    const user = userEvent.setup()
    mockTestLibrarySource.mockResolvedValue({
      reachable: true,
      credentialsVerified: true,
      message: 'Der Bereich ist erreichbar.',
      s3Scopes: [],
    })
    renderWithProviders(
      <Harness
        mode="edit"
        libraryId="lib-s3"
        credentialsStored
        originalSourceUrl="https://minio.intern.example:9000"
        initial={{ ...keyed, accessKey: '', secretKey: '' }}
      />,
    )

    expect(screen.getByLabelText('Neuer Secret Key')).toBeInTheDocument()
    expect(
      screen.getByText(/Leer lassen, um den hinterlegten Schlüssel beizubehalten/),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    await waitFor(() =>
      expect(mockTestLibrarySource).toHaveBeenLastCalledWith(
        expect.objectContaining({ libraryId: 'lib-s3', sourceCredentials: undefined }),
      ),
    )

    const endpoint = screen.getByLabelText('Endpoint')
    await user.clear(endpoint)
    await user.type(endpoint, 'https://other.example:9000')
    expect(
      screen.getByText(
        /zeigt auf einen anderen Server — der hinterlegte Schlüssel gilt dort nicht/,
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Verbindung testen' })).toBeDisabled()
  })
})
