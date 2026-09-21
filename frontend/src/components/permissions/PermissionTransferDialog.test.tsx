import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import { server } from '../../mocks/server'
import PermissionTransferDialog from './PermissionTransferDialog'

function renderDialog() {
  const onClose = vi.fn()
  renderWithProviders(
    <PermissionTransferDialog
      open
      onClose={onClose}
      source={{ type: 'GROUP', id: 'group-referat-50', name: 'Referat 50' }}
      targetKinds={['GROUP']}
      scopes={['ASSET_GRANTS', 'OWNERSHIP']}
      intro="Die gewählten Wirkungen gehen an die Zielgruppe."
    />,
    { withRouter: true },
  )
  return { onClose }
}

async function chooseTarget(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('combobox', { name: 'Zielgruppe' }))
  await user.click(await screen.findByRole('option', { name: /Projektbeteiligte Phoenix/ }))
}

describe('PermissionTransferDialog', () => {
  // ADR-0036, Entscheidung 10: Die Vorschau ist Pflicht, die Bestätigung ausdrücklich.
  it('confirms only after a preview was shown', async () => {
    renderDialog()
    const user = userEvent.setup()

    expect(screen.getByRole('button', { name: /übertragung bestätigen/i })).toBeDisabled()

    await chooseTarget(user)
    await user.click(screen.getByRole('button', { name: /vorschau erstellen/i }))

    expect(await screen.findByText(/12 Berechtigungen an 7 Objekten/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /übertragung bestätigen/i })).toBeEnabled()
  })

  it('carries the preview id into the transfer and reports the result', async () => {
    const { onClose } = renderDialog()
    const user = userEvent.setup()

    await chooseTarget(user)
    await user.click(screen.getByRole('button', { name: /vorschau erstellen/i }))
    await screen.findByText(/12 Berechtigungen an 7 Objekten/)
    await user.click(screen.getByRole('button', { name: /übertragung bestätigen/i }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  // Der Stand hat sich seit der Vorschau geändert: nichts wurde übertragen, es wird neu vorgelegt.
  it('explains a drifted preview instead of showing the raw conflict', async () => {
    server.use(
      http.post('/api/v1/permission-transfers', () =>
        HttpResponse.json(
          { error: 'No valid preview', code: 'TRANSFER_PREVIEW_REQUIRED' },
          { status: 409 },
        ),
      ),
    )
    renderDialog()
    const user = userEvent.setup()

    await chooseTarget(user)
    await user.click(screen.getByRole('button', { name: /vorschau erstellen/i }))
    await screen.findByText(/12 Berechtigungen an 7 Objekten/)
    await user.click(screen.getByRole('button', { name: /übertragung bestätigen/i }))

    expect(await screen.findByText(/Stand hat sich seit der Vorschau geändert/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /übertragung bestätigen/i })).toBeDisabled()
  })
})
