import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import TextField from '@mui/material/TextField'
import { renderWithProviders } from '@/test/test-utils'
import SettingValueRow from './SettingValueRow'

/** A period in days; `store` answers whether the new value was taken. */
function PeriodRow({ store }: { store: (days: number) => Promise<boolean> }) {
  const [days, setDays] = useState(30)
  const [draft, setDraft] = useState('30')
  const parsed = /^\d+$/.test(draft) ? Number(draft) : null
  return (
    <SettingValueRow
      title="Löschfrist"
      value={`${days} Tage`}
      onOpen={() => setDraft(String(days))}
      onSave={async () => {
        if (parsed === null) return false
        if (parsed === days) return true
        if (!(await store(parsed))) return false
        setDays(parsed)
        return true
      }}
      editor={
        <TextField
          label="Löschfrist in Tagen"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          error={parsed === null}
          helperText={parsed === null ? 'Bitte eine ganze Zahl eingeben.' : undefined}
        />
      }
    >
      <p>Werkseinstellung</p>
    </SettingValueRow>
  )
}

describe('SettingValueRow', () => {
  it('shows the value with „Ändern“ and no field or save button', () => {
    renderWithProviders(<PeriodRow store={vi.fn()} />)

    expect(screen.getByRole('heading', { name: 'Löschfrist' })).toBeInTheDocument()
    expect(screen.getByText('30 Tage')).toBeInTheDocument()
    expect(screen.getByText('Werkseinstellung')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Löschfrist ändern' })).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Speichern' })).not.toBeInTheDocument()
  })

  it('opens the field, saves and returns the focus to „Ändern“', async () => {
    const user = userEvent.setup()
    const store = vi.fn().mockResolvedValue(true)
    renderWithProviders(<PeriodRow store={store} />)

    await user.click(screen.getByRole('button', { name: 'Löschfrist ändern' }))
    const field = screen.getByLabelText('Löschfrist in Tagen')
    await user.clear(field)
    await user.type(field, '90')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(store).toHaveBeenCalledWith(90)
    expect(await screen.findByText('90 Tage')).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Löschfrist ändern' })).toHaveFocus(),
    )
  })

  it('keeps „Speichern“ active and stays open with the focus in a wrong entry', async () => {
    const user = userEvent.setup()
    const store = vi.fn()
    renderWithProviders(<PeriodRow store={store} />)

    await user.click(screen.getByRole('button', { name: 'Löschfrist ändern' }))
    const field = screen.getByLabelText('Löschfrist in Tagen')
    await user.clear(field)
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(store).not.toHaveBeenCalled()
    expect(screen.getByText('Bitte eine ganze Zahl eingeben.')).toBeInTheDocument()
    await waitFor(() => expect(field).toHaveFocus())
  })

  it('stays open when the save is declined or fails', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PeriodRow store={vi.fn().mockResolvedValue(false)} />)

    await user.click(screen.getByRole('button', { name: 'Löschfrist ändern' }))
    await user.type(screen.getByLabelText('Löschfrist in Tagen'), '0')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(await screen.findByLabelText('Löschfrist in Tagen')).toHaveValue('300')
    expect(screen.getByText('30 Tage')).toBeInTheDocument()
  })

  it('closes on „Abbrechen“ and forgets the draft', async () => {
    const user = userEvent.setup()
    const store = vi.fn()
    renderWithProviders(<PeriodRow store={store} />)

    await user.click(screen.getByRole('button', { name: 'Löschfrist ändern' }))
    await user.type(screen.getByLabelText('Löschfrist in Tagen'), '0')
    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))
    await user.click(screen.getByRole('button', { name: 'Löschfrist ändern' }))

    expect(store).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Löschfrist in Tagen')).toHaveValue('30')
  })

  it('has no action without the right to change', () => {
    renderWithProviders(
      <SettingValueRow
        title="Löschfrist"
        value="30 Tage"
        canEdit={false}
        onOpen={vi.fn()}
        onSave={vi.fn()}
        editor={null}
      />,
    )

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
