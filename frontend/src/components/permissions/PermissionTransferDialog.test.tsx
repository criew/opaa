import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../../test/test-utils'
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
      intro="Die gewählten Rechte gehen an die Zielgruppe."
    />,
    { withRouter: true },
  )
  return { onClose }
}

/**
 * Die Zielgruppe kommt aus der gemeinsamen Gruppensuche (#1820): serverseitig, ab zwei Zeichen und
 * hinter einer Entprellung von 300 ms.
 */
async function chooseTarget(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Zielgruppe'), 'Referat 5')
  // „Referat 50" ist hier die Quelle und deshalb ausgeschlossen - gewählt wird das Projektteam.
  const option = await screen.findByRole(
    'option',
    { name: /Referat 5 Projektteam/ },
    { timeout: 3000 },
  )
  await user.click(option)
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

  // ADR-0036, Entscheidung 2: Eine Gruppe eines externen Anbieters als Ziel verlangt eine
  // ausdrückliche Zwischenfrage - abgebrochen geht keine Vorschau hinaus.
  it('asks back before previewing a transfer to a group of an external provider', async () => {
    let previews = 0
    server.use(
      http.post('/api/v1/permission-transfers/preview', () => {
        previews += 1
        return HttpResponse.json({}, { status: 200 })
      }),
    )
    renderDialog()
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Zielgruppe'), 'Referat 50')
    const external = await screen.findByRole(
      'option',
      { name: /Verzeichnis Partner/ },
      { timeout: 3000 },
    )
    await user.click(external)
    await user.click(screen.getByRole('button', { name: /vorschau erstellen/i }))

    await answerConfirm(
      user,
      'Sie geben für eine Gruppe eines externen Anbieters frei — fortfahren?',
      'Abbrechen',
    )
    expect(previews).toBe(0)
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
