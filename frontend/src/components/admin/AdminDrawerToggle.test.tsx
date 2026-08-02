import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, beforeEach } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import AdminDrawerToggle from './AdminDrawerToggle'
import { useIndexingStore } from '../../stores/indexingStore'

describe('AdminDrawerToggle', () => {
  beforeEach(() => {
    useIndexingStore.setState({ drawerOpen: false })
  })

  it('renders toggle button', () => {
    renderWithProviders(<AdminDrawerToggle />)

    expect(screen.getByLabelText('Admin-Bereich ein- oder ausblenden')).toBeInTheDocument()
  })

  it('toggles drawer on click', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AdminDrawerToggle />)

    await user.click(screen.getByLabelText('Admin-Bereich ein- oder ausblenden'))
    expect(useIndexingStore.getState().drawerOpen).toBe(true)

    await user.click(screen.getByLabelText('Admin-Bereich ein- oder ausblenden'))
    expect(useIndexingStore.getState().drawerOpen).toBe(false)
  })
})
