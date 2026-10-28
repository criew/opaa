import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import { useUiStore } from '@/shared/stores/uiStore'
import CollapsibleSectionHead from './CollapsibleSectionHead'

describe('CollapsibleSectionHead', () => {
  afterEach(() => useUiStore.setState({ collapsedSidebarSections: [] }))

  it('folds its group as a disclosure and remembers that under its key', async () => {
    renderWithProviders(
      <CollapsibleSectionHead sectionKey="chats.pinned" label="Angeheftet" count={8} controls="r" />,
    )
    // The count is visible text, so the accessible name equals what is shown (no aria-label).
    const toggle = screen.getByRole('button', { name: 'Angeheftet 8' })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(toggle).toHaveAttribute('aria-controls', 'r')
    expect(toggle).not.toHaveAttribute('aria-label')

    await userEvent.click(toggle)

    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(useUiStore.getState().collapsedSidebarSections).toEqual(['chats.pinned'])
  })

  it('sits inside a heading of the given level and keeps its actions outside the toggle', () => {
    renderWithProviders(
      <CollapsibleSectionHead
        sectionKey="spaces"
        label="Spaces"
        count={3}
        controls="r"
        component="h2"
        actions={<button type="button">Neuer Space</button>}
      />,
    )
    expect(screen.getByRole('heading', { level: 2, name: 'Spaces 3' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Spaces 3' })).not.toContainElement(
      screen.getByRole('button', { name: 'Neuer Space' }),
    )
  })
})
