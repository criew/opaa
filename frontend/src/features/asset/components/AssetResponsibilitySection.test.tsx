import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import type { AssetResponsibilityResponse } from '@/shared/api/types'
import AssetResponsibilitySection from './AssetResponsibilitySection'
import { responsibilityNote } from './responsibility'

const unit: AssetResponsibilityResponse = {
  function: 'Referat Z3 – Organisation',
  missing: false,
}

function renderSection(
  props: Partial<React.ComponentProps<typeof AssetResponsibilitySection>> = {},
) {
  const handlers = {
    onSave: vi.fn(async () => undefined),
    onLock: vi.fn(async () => undefined),
    onUnlock: vi.fn(async () => undefined),
  }
  renderWithProviders(
    <AssetResponsibilitySection
      idPrefix="test"
      name="Vergabe-Assistent"
      responsibility={unit}
      canEdit
      canLock
      groups={[]}
      lockConsequence="Der Agent erscheint nicht mehr in der Auswahl."
      {...handlers}
      {...props}
    />,
  )
  return handlers
}

describe('AssetResponsibilitySection', () => {
  it('does not save an empty unit and names the field instead', async () => {
    const user = userEvent.setup()
    const { onSave } = renderSection()

    await user.click(screen.getByRole('button', { name: /^Ändern\s?Verantwortliche Stelle$/ }))
    await user.clear(screen.getByRole('combobox', { name: 'Verantwortliche Stelle' }))
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(
      screen.getByText(
        'Geben Sie die verantwortliche Stelle an: eine Gruppe, Organisationseinheit oder Funktion.',
      ),
    ).toBeInTheDocument()
    expect(onSave).not.toHaveBeenCalled()

    await user.type(screen.getByRole('combobox', { name: 'Verantwortliche Stelle' }), 'Referat 50')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    expect(onSave).toHaveBeenCalledWith({ groupId: null, function: 'Referat 50' })
  })

  it('locks only with a reason, from a dialog titled like its button', async () => {
    const user = userEvent.setup()
    const { onLock } = renderSection()

    await user.click(screen.getByRole('button', { name: /^Sperren\s?Sperre$/ }))
    const dialog = screen.getByRole('dialog', { name: 'Sperren' })
    const submit = within(dialog).getByRole('button', { name: 'Sperren' })
    expect(submit).toBeEnabled()
    await user.click(submit)
    expect(within(dialog).getByText('Geben Sie an, warum gesperrt wird.')).toBeInTheDocument()
    expect(onLock).not.toHaveBeenCalled()

    await user.type(within(dialog).getByLabelText('Grund'), '  Novelle  ')
    await user.click(submit)
    expect(onLock).toHaveBeenCalledWith('Novelle')
  })

  it('asks before unlocking and offers no lock action without the system role', async () => {
    const user = userEvent.setup()
    const locked: AssetResponsibilityResponse = {
      ...unit,
      lock: { cause: 'MANUAL', reason: 'Novelle', lockedAt: '2026-11-10T08:00:00Z' },
    }
    const { onUnlock } = renderSection({ responsibility: locked })

    expect(screen.getByText('Gesperrt')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^Entsperren\s?Sperre$/ }))
    await answerConfirm(user, '„Vergabe-Assistent“ entsperren?', 'Entsperren')
    expect(onUnlock).toHaveBeenCalledTimes(1)
  })

  it('shows no lock action to a manager without the system role', () => {
    renderSection({ canLock: false })
    expect(screen.queryByRole('button', { name: /^Sperren/ })).not.toBeInTheDocument()
  })
})

describe('responsibilityNote', () => {
  it('names the lock date of a missing unit and the reason of a lock', () => {
    expect(responsibilityNote(unit)).toBeNull()
    expect(
      responsibilityNote({ missing: true, lockDueAt: '2026-12-10T08:00:00Z' })?.text,
    ).toContain('am 10.12.2026 gesperrt')
    expect(
      responsibilityNote({
        missing: false,
        lock: { cause: 'MANUAL', reason: 'Novelle', lockedAt: '2026-11-10T08:00:00Z' },
      }),
    ).toEqual({
      severity: 'error',
      text: 'Gesperrt seit 10.11.2026 durch die Systemverwaltung: Novelle',
    })
  })
})
