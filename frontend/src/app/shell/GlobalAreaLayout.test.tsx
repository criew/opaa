import { render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { ThemeProvider } from '@mui/material/styles'
import type { PaletteMode } from '@mui/material'
import { createAppTheme } from '@/shared/theme/theme'
import GlobalAreaLayout from './GlobalAreaLayout'
import { isGlobalAreaPath } from '@/shared/layouts/globalArea'
import { useRuntimeSwitchStore } from '@/shared/session/runtimeSwitches'
import { setViewportWidth } from '@/test/viewport'

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
  it('renders the secondary column as a nav landmark with title, badge and every section', () => {
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

const MANY_SECTIONS = [
  { label: 'Allgemein & Branding', to: '/admin/branding' },
  { label: 'Benutzer', to: '/admin/users' },
  { label: 'Gruppen', to: '/admin/groups' },
  { label: 'Modelle', to: '/admin/models' },
  { label: 'Suche & Indexierung', to: '/admin/search' },
]

function renderNarrow(initialPath: string) {
  return render(
    <ThemeProvider theme={createAppTheme('light')}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route element={<GlobalAreaLayout title="Administration" sections={MANY_SECTIONS} />}>
            <Route path="/admin/*" element={<div>Seiteninhalt</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )
}

const ROW_WIDTH = 300
const ENTRY_WIDTH = 100

/**
 * jsdom has no layout: every entry is laid out 100 px wide one after the other, the row shows
 * 300 px of them. Scroll positions are kept per element and clamped like a browser does.
 */
function stubRowLayout() {
  const scroll = new WeakMap<Element, number>()
  const total = MANY_SECTIONS.length * ENTRY_WIDTH
  const isRow = (el: HTMLElement) => el.tagName === 'UL'
  const props: Record<string, PropertyDescriptor> = {
    offsetLeft: {
      configurable: true,
      get(this: HTMLElement) {
        const index = MANY_SECTIONS.findIndex((section) => section.label === this.textContent)
        return Math.max(index, 0) * ENTRY_WIDTH
      },
    },
    offsetWidth: { configurable: true, get: () => ENTRY_WIDTH },
    clientWidth: {
      configurable: true,
      get(this: HTMLElement) {
        return isRow(this) ? ROW_WIDTH : ENTRY_WIDTH
      },
    },
    scrollWidth: {
      configurable: true,
      get(this: HTMLElement) {
        return isRow(this) ? total : ENTRY_WIDTH
      },
    },
    scrollLeft: {
      configurable: true,
      get(this: HTMLElement) {
        return scroll.get(this) ?? 0
      },
      set(this: HTMLElement, value: number) {
        scroll.set(this, Math.max(0, Math.min(value, total - ROW_WIDTH)))
      },
    },
  }
  const originals = Object.keys(props).map(
    (key) => [key, Object.getOwnPropertyDescriptor(HTMLElement.prototype, key)] as const,
  )
  Object.defineProperties(HTMLElement.prototype, props)
  return () => {
    for (const [key, descriptor] of originals) {
      if (descriptor) Object.defineProperty(HTMLElement.prototype, key, descriptor)
      else Reflect.deleteProperty(HTMLElement.prototype, key)
    }
  }
}

describe('GlobalAreaLayout below 900 px', () => {
  const originalMatchMedia = window.matchMedia
  let restoreLayout: () => void

  beforeEach(() => {
    setViewportWidth(360)
    restoreLayout = stubRowLayout()
  })

  afterEach(() => {
    window.matchMedia = originalMatchMedia
    restoreLayout()
  })

  it('shows the sections as one list of links in the nav landmark, without the column title', () => {
    renderNarrow('/admin/groups')

    const nav = screen.getByRole('navigation', { name: 'Administration' })
    const list = within(nav).getByRole('list')
    expect(within(list).getAllByRole('listitem')).toHaveLength(MANY_SECTIONS.length)
    expect(
      within(list)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual(MANY_SECTIONS.map((section) => section.label))
    // Title and badge of the column would push the page down; the landmark keeps the name.
    expect(screen.queryByText('Global')).not.toBeInTheDocument()
    expect(screen.getByText('Seiteninhalt')).toBeInTheDocument()
  })

  it('marks the current entry and scrolls it to the middle of the row', () => {
    renderNarrow('/admin/groups')

    expect(screen.getByRole('link', { name: 'Gruppen' })).toHaveAttribute('aria-current', 'page')
    const row = screen.getByRole('list')
    // entry 3 of 5 starts at 200 px; centred in a 300 px row the row scrolls by 100 px
    expect(row.scrollLeft).toBe(100)
    // entries beyond both edges: both edges fade
    expect(row).toHaveAttribute('data-edge-start', 'true')
    expect(row).toHaveAttribute('data-edge-end', 'true')
  })

  it('fades only the far edge while the first entry is current', () => {
    renderNarrow('/admin/branding')

    const row = screen.getByRole('list')
    expect(row.scrollLeft).toBe(0)
    expect(row).not.toHaveAttribute('data-edge-start')
    expect(row).toHaveAttribute('data-edge-end', 'true')
  })

  it('keeps a section marked on its subroutes and stops at the end of the row', () => {
    renderNarrow('/admin/search/status')

    expect(screen.getByRole('link', { name: 'Suche & Indexierung' })).toHaveAttribute(
      'aria-current',
      'true',
    )
    const row = screen.getByRole('list')
    expect(row.scrollLeft).toBe(200)
    expect(row).toHaveAttribute('data-edge-start', 'true')
    expect(row).not.toHaveAttribute('data-edge-end')
  })
})

describe('isGlobalAreaPath', () => {
  it('matches every global scope and nothing that merely shares a prefix string', () => {
    expect(isGlobalAreaPath('/admin/groups')).toBe(true)
    expect(isGlobalAreaPath('/admin')).toBe(true)
    expect(isGlobalAreaPath('/administrator')).toBe(false)
    // Overview and create wizard keep the space column (guidelines 5.13), like a chosen space.
    expect(isGlobalAreaPath('/spaces')).toBe(false)
    expect(isGlobalAreaPath('/spaces/new')).toBe(false)
    expect(isGlobalAreaPath('/spaces/')).toBe(false)
    // React Router matches trailing slashes - the comparison must tolerate them just the same.
    expect(isGlobalAreaPath('/libraries/')).toBe(true)
    expect(isGlobalAreaPath('/spaces/space-1')).toBe(false)
    expect(isGlobalAreaPath('/spaces/space-1/chats/chat-1')).toBe(false)
    // The user settings are a global area as well.
    expect(isGlobalAreaPath('/settings')).toBe(true)
    // The library catalog is a global area as well (Schlussnotiz Abschnitt 2).
    expect(isGlobalAreaPath('/libraries')).toBe(true)
    expect(isGlobalAreaPath('/libraries/lib-1')).toBe(true)
    // The audit's point-in-time report renders in the global frame as well.
    expect(isGlobalAreaPath('/revision/rechtehistorie')).toBe(true)
    expect(isGlobalAreaPath('/revisionen')).toBe(false)
  })

  // regression guard: the template wizard rendered beside the chat column while the runtime
  // switch "Vorlagen" was not yet read, since the shell does not re-render when it is
  it('keeps the template pages in the catalog frame whatever the runtime switch says', () => {
    useRuntimeSwitchStore.setState({ documentTemplates: false })

    expect(isGlobalAreaPath('/templates/new')).toBe(true)
    expect(isGlobalAreaPath('/templates/tpl-1')).toBe(true)
  })
})
