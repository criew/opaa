import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MemoryRouter } from 'react-router'
import { ThemeProvider } from '@mui/material/styles'
import { createAppTheme } from '../../theme/theme'
import AdminSectionNav from './AdminSectionNav'

function renderAt(initialPath: string) {
  return render(
    <ThemeProvider theme={createAppTheme('light')}>
      <MemoryRouter initialEntries={[initialPath]}>
        <AdminSectionNav />
      </MemoryRouter>
    </ThemeProvider>,
  )
}

// Reachability bridge until #787 delivers the admin secondary column (review #791, finding 1):
// without these links, /admin/models and /admin/branding had no entry point in the interface.
describe('AdminSectionNav', () => {
  it('links every administration page', () => {
    renderAt('/admin/groups')

    expect(screen.getByRole('navigation', { name: 'Administration' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Gruppen' })).toHaveAttribute('href', '/admin/groups')
    expect(screen.getByRole('link', { name: 'Branding' })).toHaveAttribute(
      'href',
      '/admin/branding',
    )
    expect(screen.getByRole('link', { name: 'Modelle' })).toHaveAttribute('href', '/admin/models')
  })

  it('marks the current page', () => {
    renderAt('/admin/models')

    expect(screen.getByRole('link', { name: 'Modelle' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Gruppen' })).not.toHaveAttribute('aria-current')
  })
})
