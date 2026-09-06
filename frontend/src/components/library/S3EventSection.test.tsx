import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import { useLibraryStore } from '../../stores/libraryStore'
import S3EventSection from './S3EventSection'

const { mockGenerate, mockRemove } = vi.hoisted(() => ({
  mockGenerate: vi.fn(),
  mockRemove: vi.fn(),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    generateS3EventsToken: mockGenerate,
    removeS3EventsToken: mockRemove,
  }
})

const scopes = [
  { bucket: 'dokumente', prefix: '2025/' },
  { bucket: 'satzungen', prefix: null },
]

describe('S3EventSection (#1381, ADR-0027)', () => {
  const loadLibraryDetails = vi.fn().mockResolvedValue(undefined)

  beforeEach(() => {
    mockGenerate.mockReset()
    mockRemove.mockReset()
    loadLibraryDetails.mockClear()
    useLibraryStore.setState({ loadLibraryDetails })
  })

  it('offers to set up notifications and reveals the token exactly once with the endpoint and the provider commands', async () => {
    mockGenerate.mockResolvedValue({
      token: 'ereignis-token-43',
      path: '/api/v1/libraries/lib-1/s3-events',
    })
    renderWithProviders(<S3EventSection libraryId="lib-1" tokenSet={false} scopes={scopes} />)
    const user = userEvent.setup()

    expect(screen.getByText(/nicht eingerichtet/)).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Benachrichtigung entfernen' }),
    ).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Benachrichtigung einrichten' }))

    expect(mockGenerate).toHaveBeenCalledWith('lib-1')
    const dialog = await screen.findByRole('dialog', { name: 'Ereignis-Token' })
    expect(screen.getByTestId('s3-event-token')).toHaveTextContent('ereignis-token-43')
    expect(screen.getByTestId('s3-event-endpoint')).toHaveTextContent(
      `${window.location.origin}/api/v1/libraries/lib-1/s3-events`,
    )
    expect(dialog).toHaveTextContent(/nur jetzt angezeigt/)
    expect(dialog).toHaveTextContent('auth_token="ereignis-token-43"')
    expect(dialog).toHaveTextContent(
      'mc event add ALIAS/dokumente arn:minio:sqs::opaa:webhook --event put,delete --prefix "2025/"',
    )
    expect(dialog).toHaveTextContent('mc event add ALIAS/satzungen')
    expect(dialog).toHaveTextContent(/X-OPAA-Webhook-Secret/)
    expect(dialog).toHaveTextContent('opaa:ereignis-token-43@')
    expect(loadLibraryDetails).toHaveBeenCalledWith('lib-1')

    await user.click(screen.getByRole('button', { name: 'Schließen' }))
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Ereignis-Token' })).not.toBeInTheDocument(),
    )
  })

  it('asks before removing an existing token and reloads the library afterwards', async () => {
    mockRemove.mockResolvedValue(undefined)
    renderWithProviders(<S3EventSection libraryId="lib-1" tokenSet={true} scopes={scopes} />)
    const user = userEvent.setup()

    expect(
      screen.getByText(/eingerichtet — Änderungen werden sofort aufgenommen/),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Benachrichtigung entfernen' }))
    expect(mockRemove).not.toHaveBeenCalled()
    const confirmDialog = await screen.findByRole('dialog', {
      name: 'Benachrichtigung entfernen?',
    })
    expect(confirmDialog).toHaveTextContent(/kann OPAA danach nicht mehr benachrichtigen/)
    await user.click(screen.getByRole('button', { name: 'Entfernen' }))

    expect(mockRemove).toHaveBeenCalledWith('lib-1')
    await waitFor(() => expect(loadLibraryDetails).toHaveBeenCalledWith('lib-1'))
  })

  it('asks before rotating and shows the API error when generating fails', async () => {
    mockGenerate.mockRejectedValue(new Error('Kein Zugriff auf diese Bibliothek'))
    renderWithProviders(<S3EventSection libraryId="lib-1" tokenSet={true} scopes={scopes} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Token neu erzeugen' }))
    await screen.findByRole('dialog', { name: 'Token neu erzeugen?' })
    await user.click(screen.getByRole('button', { name: 'Neu erzeugen' }))

    expect(mockGenerate).toHaveBeenCalledWith('lib-1')
    expect(await screen.findByRole('alert')).toHaveTextContent('Kein Zugriff auf diese Bibliothek')
    expect(screen.queryByRole('dialog', { name: 'Ereignis-Token' })).not.toBeInTheDocument()
  })
})
