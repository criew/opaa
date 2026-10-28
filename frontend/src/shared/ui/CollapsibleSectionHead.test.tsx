import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import { useUiStore } from '@/shared/stores/uiStore'
import CollapsibleSectionHead from './CollapsibleSectionHead'

describe('CollapsibleSectionHead', () => {
  afterEach(() => useUiStore.setState({ collapsedSidebarSections: [] }))

  it('folds its section and remembers that under its key', async () => {
    renderWithProviders(
      <CollapsibleSectionHead
        sectionKey="chats.pinned"
        label="Angeheftet"
        count={8}
        controls="r"
      />,
    )
    const toggle = screen.getByRole('button', { name: 'Angeheftet 8' })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(toggle).toHaveAttribute('aria-controls', 'r')

    await userEvent.click(toggle)

    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(useUiStore.getState().collapsedSidebarSections).toEqual(['chats.pinned'])
  })
})
