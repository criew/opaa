import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { ThemeProvider } from '@mui/material/styles'
import type { PaletteMode } from '@mui/material'
import { createAppTheme } from '../theme/theme'
import GlobalAreaLayout from './GlobalAreaLayout'
import { isGlobalAreaPath } from './globalArea'

const SECTIONS = [
  { label: 'Allgemein & Branding', to: '/admin/branding' },
  { label: 'Benutzer & Gruppen', to: '/admin/groups' },
  { label: 'Modelle', to: '/admin/models' },
]

function renderArea(initialPath: string, mode: PaletteMode = 'light') {
  return render(
    <ThemeProvider theme={createAppTheme(mode)}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route element={<GlobalAreaLayout title="Administration" sections={SECTIONS} />}>
            <Route path="/admin/groups" element={<div>Gruppen-Inhalt</div>} />
            <Route path="/admin/models" element={<div>Modelle-Inhalt</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )
}

describe('GlobalAreaLayout', () => {
  it('renders the secondary column as a nav landmark with title, badge and every section (mockup 2b)', () => {
    renderArea('/admin/groups')

    const column = screen.getByRole('navigation', { name: 'Administration' })
    expect(column).toBeInTheDocument()
    expect(screen.getByText('Administration')).toBeInTheDocument()
    // The scope is carried by visible text, not by color alone (accessibility.md).
    expect(screen.getByText('Global')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Allgemein & Branding' })).toHaveAttribute(
      'href',
      '/admin/branding',
    )
    expect(screen.getByRole('link', { name: 'Benutzer & Gruppen' })).toHaveAttribute(
      'href',
      '/admin/groups',
    )
    expect(screen.getByRole('link', { name: 'Modelle' })).toHaveAttribute('href', '/admin/models')
  })

  it('marks the current page and renders the routed content', () => {
    renderArea('/admin/models')

    expect(screen.getByRole('link', { name: 'Modelle' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Benutzer & Gruppen' })).not.toHaveAttribute(
      'aria-current',
    )
    expect(screen.getByText('Modelle-Inhalt')).toBeInTheDocument()
  })

  it('keeps a section marked on its subroutes with aria-current="true" (#800)', () => {
    render(
      <ThemeProvider theme={createAppTheme('light')}>
        <MemoryRouter initialEntries={['/admin/models/model-1']}>
          <Routes>
            <Route element={<GlobalAreaLayout title="Administration" sections={SECTIONS} />}>
              <Route path="/admin/models/:id" element={<div>Detail</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </ThemeProvider>,
    )

    expect(screen.getByRole('link', { name: 'Modelle' })).toHaveAttribute('aria-current', 'true')
  })

  it('renders in the dark scheme as well', () => {
    renderArea('/admin/groups', 'dark')

    expect(screen.getByRole('navigation', { name: 'Administration' })).toBeInTheDocument()
    expect(screen.getByText('Gruppen-Inhalt')).toBeInTheDocument()
  })

  it('renders no column without sections - the bare frame for #788/#789', () => {
    render(
      <ThemeProvider theme={createAppTheme('light')}>
        <MemoryRouter initialEntries={['/settings']}>
          <Routes>
            <Route element={<GlobalAreaLayout />}>
              <Route path="/settings" element={<div>Einstellungen-Inhalt</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </ThemeProvider>,
    )

    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(screen.getByText('Einstellungen-Inhalt')).toBeInTheDocument()
  })
})

describe('isGlobalAreaPath', () => {
  it('matches every global scope and nothing that merely shares a prefix string', () => {
    expect(isGlobalAreaPath('/admin/groups')).toBe(true)
    expect(isGlobalAreaPath('/admin')).toBe(true)
    expect(isGlobalAreaPath('/administrator')).toBe(false)
    expect(isGlobalAreaPath('/spaces/space-1')).toBe(false)
    // Since #788 the user settings are a global area as well (mockup 2c).
    expect(isGlobalAreaPath('/settings')).toBe(true)
    // Since #789 the library catalog is a global area as well (Schlussnotiz Abschnitt 2).
    expect(isGlobalAreaPath('/libraries')).toBe(true)
    expect(isGlobalAreaPath('/libraries/lib-1')).toBe(true)
  })
})
