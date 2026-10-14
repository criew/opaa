import { lazy } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { ThemeProvider } from '@mui/material/styles'
import { createAppTheme } from '@/shared/theme/theme'
import GlobalAreaLayout from './shell/GlobalAreaLayout'
import { loadOrWait } from './lazyPages'

const SECTIONS = [{ label: 'Gruppen', to: '/admin/groups' }]

function renderAreaWith(page: React.ReactNode) {
  return render(
    <ThemeProvider theme={createAppTheme('light')}>
      <MemoryRouter initialEntries={['/admin/groups']}>
        <Routes>
          <Route element={<GlobalAreaLayout title="Administration" sections={SECTIONS} />}>
            <Route path="/admin/groups" element={page} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )
}

describe('lazy pages', () => {
  it('shows a labelled loading indicator inside the layout while the page chunk loads', async () => {
    let resolve: (module: { default: () => React.ReactNode }) => void = () => {}
    const Page = lazy(() => new Promise<{ default: () => React.ReactNode }>((r) => (resolve = r)))

    renderAreaWith(<Page />)

    // The area navigation stays; only the content waits.
    expect(screen.getByRole('navigation', { name: 'Administration' })).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Seite wird geladen' })).toBeInTheDocument()

    resolve({ default: () => <div>Gruppen-Inhalt</div> })

    expect(await screen.findByText('Gruppen-Inhalt')).toBeInTheDocument()
    expect(
      screen.queryByRole('progressbar', { name: 'Seite wird geladen' }),
    ).not.toBeInTheDocument()
  })

  it('resolves with the loaded module', async () => {
    const module = { default: 'page' }
    await expect(loadOrWait(() => Promise.resolve(module))).resolves.toBe(module)
  })

  it('stays pending when a failed load was answered with a reload', async () => {
    // The preload helper resolves with undefined once the vite:preloadError event is prevented.
    const pending = loadOrWait(() => Promise.resolve(undefined as unknown as { default: string }))
    const outcome = await Promise.race([
      pending.then(() => 'settled'),
      new Promise((r) => setTimeout(() => r('pending'), 50)),
    ])
    expect(outcome).toBe('pending')
  })

  it('keeps the loading indicator instead of an error while the reload is under way', async () => {
    const Page = lazy(() =>
      loadOrWait(() => Promise.resolve(undefined as unknown as { default: () => null })),
    )

    renderAreaWith(<Page />)
    await new Promise((r) => setTimeout(r, 50))

    expect(screen.getByRole('progressbar', { name: 'Seite wird geladen' })).toBeInTheDocument()
  })
})
