import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import ConfirmHost from './ConfirmHost'
import { confirmAction, useConfirmStore } from '../stores/confirmStore'

/**
 * Der Quelltext des Frontends, von Vite eingelesen (`?raw`) - kein Dateisystemzugriff, damit der
 * Wächter unten ohne Node-Typen auskommt.
 */
const QUELLEN = {
  ...(import.meta.glob('../**/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../**/*.ts', {
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
    // renderWithProviders montiert den Host selbst; hier steht er zusätzlich nicht.
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
    // Das Verb, nicht „OK" - wer nur die Schaltfläche liest, weiß trotzdem, was er zusagt.
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

    // Ein Screenreader liest beim Öffnen beides vor - die Folge ist die Entscheidungsgrundlage
    // und darf nicht erst beim Durchtabben auffallen.
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

    // Escape bestätigt nie - sonst löschte ein Fluchtreflex das Objekt.
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

    // Ein Enter aus dem Reflex heraus bricht dann ab, statt zu löschen.
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

    // `window.confirm` blockierte den Thread und konnte sich nicht überlagern. Ohne diese Regel
    // bliebe das `await` der verdrängten Anfrage für immer stehen.
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
   * Die Regel aus #1610, maschinell gezogen: Der Browser-Dialog ist aus dem Produktivcode
   * verschwunden und kommt nicht durch die Hintertür zurück. Er blockiert den Haupt-Thread,
   * benennt seine Handlung nicht und lässt sich nicht gestalten.
   */
  it('leaves no window.confirm in the product code', () => {
    // `\bconfirm\(` trifft `window.confirm(` und ein nacktes `confirm(`, nicht aber
    // `confirmAction(` - dort folgt auf „confirm" ein Buchstabe, keine Klammer.
    const rueckfaelle = PRODUKTIVDATEIEN.filter((pfad) => /\bconfirm\(/.test(QUELLEN[pfad]))

    expect(rueckfaelle).toEqual([])
  })
})
