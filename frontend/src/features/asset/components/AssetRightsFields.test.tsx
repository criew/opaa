import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import AssetRightsFields from './AssetRightsFields'

describe('AssetRightsFields', () => {
  // P28: „Vormerken“ stays active; a missing choice is named at the search field.
  it('keeps „Vormerken“ active without a choice and notes nothing', async () => {
    const onPendingGrantsChange = vi.fn()
    renderWithProviders(
      <AssetRightsFields pendingGrants={[]} onPendingGrantsChange={onPendingGrantsChange} />,
    )
    const user = userEvent.setup()

    const note = screen.getByRole('button', { name: 'Vormerken' })
    expect(note).toBeEnabled()
    await user.click(note)

    const search = screen.getByRole('combobox', { name: 'Person oder Gruppe suchen' })
    expect(search).toHaveAccessibleDescription('Bitte eine Person oder Gruppe auswählen.')
    expect(search).toHaveFocus()
    expect(onPendingGrantsChange).not.toHaveBeenCalled()
  })
})
