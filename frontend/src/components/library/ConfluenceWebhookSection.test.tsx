import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import { useLibraryStore } from '../../stores/libraryStore'
import ConfluenceWebhookSection from './ConfluenceWebhookSection'

const { mockGenerate, mockRemove } = vi.hoisted(() => ({
  mockGenerate: vi.fn(),
  mockRemove: vi.fn(),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    generateConfluenceWebhookSecret: mockGenerate,
    removeConfluenceWebhookSecret: mockRemove,
  }
})

describe('ConfluenceWebhookSection (#1140)', () => {
  const loadLibraryDetails = vi.fn().mockResolvedValue(undefined)

  beforeEach(() => {
    mockGenerate.mockReset()
    mockRemove.mockReset()
    loadLibraryDetails.mockClear()
    useLibraryStore.setState({ loadLibraryDetails })
  })

  it('offers to set up a webhook and reveals the secret exactly once with the endpoint address', async () => {
    mockGenerate.mockResolvedValue({
      secret: 'geheim-43-zeichen',
      path: '/api/v1/libraries/lib-1/confluence-webhook',
    })
    renderWithProviders(<ConfluenceWebhookSection libraryId="lib-1" secretSet={false} />)
    const user = userEvent.setup()

    expect(screen.getByText(/nicht eingerichtet/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Webhook entfernen' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Webhook einrichten' }))

    expect(mockGenerate).toHaveBeenCalledWith('lib-1')
    const dialog = await screen.findByRole('dialog', { name: 'Webhook-Geheimnis' })
    expect(screen.getByTestId('confluence-webhook-secret')).toHaveTextContent('geheim-43-zeichen')
    expect(dialog).toHaveTextContent(
      `${window.location.origin}/api/v1/libraries/lib-1/confluence-webhook`,
    )
    expect(dialog).toHaveTextContent(/nur jetzt angezeigt/)
    expect(dialog).toHaveTextContent(/X-Hub-Signature/)
    expect(dialog).toHaveTextContent(/X-OPAA-Webhook-Secret/)
    expect(loadLibraryDetails).toHaveBeenCalledWith('lib-1')

    await user.click(screen.getByRole('button', { name: 'Schließen' }))
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Webhook-Geheimnis' })).not.toBeInTheDocument(),
    )
  })

  it('offers rotation and removal once a secret exists', async () => {
    mockRemove.mockResolvedValue(undefined)
    renderWithProviders(<ConfluenceWebhookSection libraryId="lib-1" secretSet={true} />)
    const user = userEvent.setup()

    expect(
      screen.getByText(/eingerichtet — Änderungen werden sofort aufgenommen/),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Geheimnis neu erzeugen' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Webhook entfernen' }))
    // a stray click must not cut the connection - the removal asks first
    expect(mockRemove).not.toHaveBeenCalled()
    const confirmDialog = await screen.findByRole('dialog', { name: 'Webhook entfernen?' })
    expect(confirmDialog).toHaveTextContent(/kann OPAA danach nicht mehr benachrichtigen/)
    await user.click(screen.getByRole('button', { name: 'Entfernen' }))

    expect(mockRemove).toHaveBeenCalledWith('lib-1')
    await waitFor(() => expect(loadLibraryDetails).toHaveBeenCalledWith('lib-1'))
  })

  it('asks before rotating an existing secret and can be cancelled', async () => {
    mockGenerate.mockResolvedValue({
      secret: 'neu',
      path: '/api/v1/libraries/lib-1/confluence-webhook',
    })
    renderWithProviders(<ConfluenceWebhookSection libraryId="lib-1" secretSet={true} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Geheimnis neu erzeugen' }))
    await screen.findByRole('dialog', { name: 'Geheimnis neu erzeugen?' })
    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))
    expect(mockGenerate).not.toHaveBeenCalled()
    // MUI hides the page behind an open dialog; wait for the closing transition to finish
    await waitFor(() =>
      expect(
        screen.queryByRole('dialog', { name: 'Geheimnis neu erzeugen?' }),
      ).not.toBeInTheDocument(),
    )

    await user.click(screen.getByRole('button', { name: 'Geheimnis neu erzeugen' }))
    await user.click(await screen.findByRole('button', { name: 'Neu erzeugen' }))
    expect(mockGenerate).toHaveBeenCalledWith('lib-1')
    expect(screen.getByTestId('confluence-webhook-secret')).toHaveTextContent('neu')
  })

  it('shows the API error when generating fails', async () => {
    mockGenerate.mockRejectedValue(new Error('Kein Zugriff auf diese Bibliothek'))
    renderWithProviders(<ConfluenceWebhookSection libraryId="lib-1" secretSet={false} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Webhook einrichten' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Kein Zugriff auf diese Bibliothek')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
