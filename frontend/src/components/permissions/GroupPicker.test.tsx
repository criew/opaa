import { useState } from 'react'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { SelectableGroupResponse } from '../../types/api'
import { renderWithProviders } from '../../test/test-utils'
import GroupPicker from './GroupPicker'

function Harness() {
  const [value, setValue] = useState<SelectableGroupResponse | null>(null)
  return (
    <GroupPicker
      ariaLabel="Gruppe"
      placeholder="Gruppe suchen …"
      value={value}
      onChange={setValue}
    />
  )
}

describe('GroupPicker', () => {
  // regression guard for the transfer dialog: a chosen group must show in the field
  it('shows the chosen group in the field instead of the typed search text', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    const field = screen.getByLabelText('Gruppe')
    await user.type(field, 'Projektteam')
    const option = await screen.findByRole(
      'option',
      { name: /Referat 5 Projektteam/ },
      { timeout: 3000 },
    )
    await user.click(option)

    expect(field).toHaveValue('Referat 5 Projektteam')
  })

  it('searches again for what is typed after a choice', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    const field = screen.getByLabelText('Gruppe')
    await user.type(field, 'Projektteam')
    await user.click(
      await screen.findByRole('option', { name: /Referat 5 Projektteam/ }, { timeout: 3000 }),
    )
    await user.clear(field)
    await user.type(field, 'Referat 50')

    expect(field).toHaveValue('Referat 50')
    expect(
      await screen.findAllByRole('option', { name: /Referat 50/ }, { timeout: 3000 }),
    ).not.toHaveLength(0)
  })
})
