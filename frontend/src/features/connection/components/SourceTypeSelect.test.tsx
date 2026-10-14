import { useState } from 'react'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import type { SourceTypeDescriptor } from '@/shared/api/types'
import SourceTypeSelect from './SourceTypeSelect'

function descriptor(
  type: string,
  displayName: string,
  profileSupport: SourceTypeDescriptor['profileSupport'],
): SourceTypeDescriptor {
  return {
    type,
    displayName,
    indexingRun: true,
    uploads: false,
    profileSupport,
    profileRequired: false,
    signIns: [],
    profileDefaults: [],
    serverAddress: { fixed: null, schemes: ['https'] },
  } as unknown as SourceTypeDescriptor
}

const TYPES = [
  descriptor('FILESYSTEM', 'Dateisystem', 'FORBIDDEN'),
  descriptor('NEXTCLOUD', 'Nextcloud', 'OPTIONAL'),
  descriptor('SMB', 'Windows-Dateifreigabe (SMB)', 'OPTIONAL'),
]

function Harness({ onChange }: { onChange: (type: string) => void }) {
  const [value, setValue] = useState<string | null>(null)
  return (
    <SourceTypeSelect
      types={TYPES}
      value={value}
      onChange={(next) => {
        setValue(next)
        onChange(next)
      }}
    />
  )
}

/** Die Quellart eines neuen Zugangs als Auswahlfeld statt als Kacheln. */
describe('SourceTypeSelect', () => {
  it('nennt je Eintrag Name und Beschreibung, im Feld nur den Namen, darunter die Beschreibung', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness onChange={vi.fn()} />)

    const field = screen.getByRole('combobox', { name: /Quellart/ })
    await user.click(field)
    const option = await screen.findByRole('option', { name: /Nextcloud/ })
    expect(option).toHaveTextContent(/Ordner einer Nextcloud/)
    await user.click(option)

    expect(field).toHaveTextContent(/^Nextcloud$/)
    expect(screen.getByText(/Ordner einer Nextcloud/)).toBeVisible()
  })

  it('lässt sich ganz mit der Tastatur wählen', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<Harness onChange={onChange} />)

    await user.tab()
    expect(screen.getByRole('combobox', { name: /Quellart/ })).toHaveFocus()
    await user.keyboard('{Enter}')
    await screen.findByRole('listbox')
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')

    expect(onChange).toHaveBeenLastCalledWith('SMB')
  })

  it('zeigt eine Quellart ohne Zugänge mit Grund und übernimmt sie nicht', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<Harness onChange={onChange} />)

    await user.click(screen.getByRole('combobox', { name: /Quellart/ }))
    const locked = await screen.findByRole('option', { name: /Dateisystem/ })
    expect(locked).toHaveAttribute('aria-disabled', 'true')
    expect(within(locked).getByText(/keine Zugänge möglich/)).toBeVisible()
    await user.click(locked)

    expect(onChange).not.toHaveBeenCalled()
  })
})
