import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import ConfirmHost from './ConfirmHost'
import { confirmAction, useConfirmStore } from '../../stores/confirmStore'

/**
 * The frontend's source text, read in by Vite (`?raw`) - no file system access, so that the
 * guard below gets by without Node types.
 */
const QUELLEN = {
  ...(import.meta.glob('../../../**/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../../**/*.ts', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
}

const PRODUKTIVDATEIEN = Object.keys(QUELLEN).filter(
  (pfad) => !pfad.includes('.test.') && !pfad.includes('/test/') && !pfad.includes('/mocks/'),
)

describe('ConfirmHost', () => {
  beforeEach(() => {
    useConfirmStore.getState().reset()
  })

  it('shows the question, the consequence and the verb of the action', async () => {
    // renderWithProviders mounts the host itself; it is not added again here.
    renderWithProviders(<div />)
    void confirmAction({
      question: 'Lokale Anmeldung abschalten?',
      consequence: 'Alle lokalen Konten verlieren ihre Sitzung.',
      confirmLabel: 'Abschalten',
      tone: 'caution',
    })

    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('Lokale Anmeldung abschalten?')).toBeInTheDocument()
    expect(screen.getByText('Alle lokalen Konten verlieren ihre Sitzung.')).toBeInTheDocument()
    // The verb, not „OK" - whoever reads only the button still knows what they agree to.
    expect(screen.getByRole('button', { name: 'Abschalten' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'OK' })).toBeNull()
  })

  it('names the dialog by the question and describes it by the consequence', async () => {
    renderWithProviders(<div />)
    void confirmAction({
      question: 'Konto löschen?',
      consequence: 'Das Konto wird endgültig entfernt.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })

    // A screen reader reads both on opening - the consequence is the basis for the decision
    // and must not first be noticed while tabbing through.
    const dialog = await screen.findByRole('dialog', { name: 'Konto löschen?' })
    const beschreibung = dialog.getAttribute('aria-describedby')
    expect(beschreibung).toBeTruthy()
    expect(document.getElementById(beschreibung!)).toHaveTextContent(
      'Das Konto wird endgültig entfernt.',
    )
  })

  it('resolves true only on the confirming button', async () => {
    const user = userEvent.setup()
    renderWithProviders(<div />)
    const antwort = confirmAction({ question: 'Fortfahren?', confirmLabel: 'Fortfahren' })

    await user.click(await screen.findByRole('button', { name: 'Fortfahren' }))

    await expect(antwort).resolves.toBe(true)
  })

  it('resolves false on cancel', async () => {
    const user = userEvent.setup()
    renderWithProviders(<div />)
    const antwort = confirmAction({ question: 'Fortfahren?', confirmLabel: 'Fortfahren' })

    await user.click(await screen.findByRole('button', { name: 'Abbrechen' }))

    await expect(antwort).resolves.toBe(false)
  })

  it('resolves false on Escape', async () => {
    const user = userEvent.setup()
    renderWithProviders(<div />)
    const antwort = confirmAction({ question: 'Fortfahren?', confirmLabel: 'Fortfahren' })
    await screen.findByRole('dialog')

    await user.keyboard('{Escape}')

    // Escape never confirms - otherwise a reflex to escape would delete the object.
    await expect(antwort).resolves.toBe(false)
  })

  it('rests the focus on cancel for a destructive action', async () => {
    renderWithProviders(<div />)
    void confirmAction({
      question: 'Konto löschen?',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    await screen.findByRole('dialog')

    // An Enter out of reflex then cancels instead of deleting.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Abbrechen' })).toHaveFocus())
  })

  it('rests the focus on the action where nothing is destroyed', async () => {
    renderWithProviders(<div />)
    void confirmAction({
      question: 'Selbstregistrierung einschalten?',
      confirmLabel: 'Einschalten',
      tone: 'caution',
    })
    await screen.findByRole('dialog')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Einschalten' })).toHaveFocus())
  })

  it('answers a displaced request with false instead of leaving it pending', async () => {
    const user = userEvent.setup()
    renderWithProviders(<div />)
    const erste = confirmAction({ question: 'Erste Frage?', confirmLabel: 'Ja' })
    const zweite = confirmAction({ question: 'Zweite Frage?', confirmLabel: 'Auch ja' })

    // `window.confirm` blocked the thread and could not overlap. Without this rule
    // the `await` of the displaced request would hang forever.
    await expect(erste).resolves.toBe(false)
    expect(await screen.findByText('Zweite Frage?')).toBeInTheDocument()
    expect(screen.queryByText('Erste Frage?')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Auch ja' }))
    await expect(zweite).resolves.toBe(true)
  })

  it('closes without an open request', () => {
    renderWithProviders(<ConfirmHost />)

    expect(screen.queryByRole('dialog')).toBeNull()
  })

  /**
   * The rule drawn mechanically: the browser dialog has disappeared from production code
   * and does not come back through the back door. It blocks the main thread,
   * does not name its action and cannot be styled.
   */
  it('leaves no window.confirm in the product code', () => {
    // `\bconfirm\(` matches `window.confirm(` and a bare `confirm(`, but not
    // `confirmAction(` - there „confirm" is followed by a letter, not a parenthesis.
    const rueckfaelle = PRODUKTIVDATEIEN.filter((pfad) => /\bconfirm\(/.test(QUELLEN[pfad]))

    expect(rueckfaelle).toEqual([])
  })
})
