import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import type { Capability } from '../types/api'
import CatalogNewPage from './CatalogNewPage'

function withCapabilities(capabilities: Capability[]) {
  server.use(http.get('/api/v1/me/capabilities', () => HttpResponse.json({ capabilities })))
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/catalog/new" element={<CatalogNewPage />} />
      <Route path="/catalog" element={<div>Katalogseite</div>} />
      <Route path="/libraries/new" element={<div>Assistent Wissen</div>} />
      <Route path="/prompts/new" element={<div>Assistent Prompts</div>} />
    </Routes>,
    { withRouter: true, initialRoute: '/catalog/new' },
  )
}

const QUESTION = 'Was möchten Sie anlegen?'

describe('CatalogNewPage (ADR-0039, "Neu")', () => {
  it('offers every type as a tile in one named radio group', async () => {
    renderPage()

    expect(screen.getByRole('heading', { level: 1, name: QUESTION })).toBeInTheDocument()
    const group = await screen.findByRole('radiogroup', { name: QUESTION })
    expect(group).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Wissen/ })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('radio', { name: /Prompts/ })).toHaveTextContent(
      'Wiederkehrende Formulierungshilfen',
    )
  })

  it('goes on to the wizard of the chosen type', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('radio', { name: /Prompts/ }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    expect(await screen.findByText('Assistent Prompts')).toBeInTheDocument()
  })

  it('is operated by keyboard: arrows choose, Enter on "Weiter" goes on', async () => {
    const user = userEvent.setup()
    renderPage()

    const knowledge = await screen.findByRole('radio', { name: /Wissen/ })
    knowledge.focus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('radio', { name: /Prompts/ })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: 'Weiter' })).toHaveFocus()
    await user.keyboard('{Enter}')

    expect(await screen.findByText('Assistent Prompts')).toBeInTheDocument()
  })

  it('leaves out a type without the creation right', async () => {
    withCapabilities(['CREATE_PROMPT_LIBRARY'])
    renderPage()

    await waitFor(() => expect(screen.queryByRole('radio', { name: /Wissen/ })).not.toBeInTheDocument())
    expect(screen.getByRole('radio', { name: /Prompts/ })).toHaveAttribute('aria-checked', 'true')
  })

  it('offers knowledge to a person who may only create connector libraries', async () => {
    withCapabilities(['CREATE_CONNECTOR_LIBRARY'])
    renderPage()

    await waitFor(() =>
      expect(screen.queryByRole('radio', { name: /Prompts/ })).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('radio', { name: /Wissen/ })).toBeInTheDocument()
  })

  it('explains a missing creation right instead of offering an empty choice', async () => {
    withCapabilities([])
    renderPage()

    expect(await screen.findByText(/Ihnen fehlt ein Anlegerecht/)).toBeInTheDocument()
    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Weiter' })).not.toBeInTheDocument()
  })

  it('returns to the catalog on "Abbrechen"', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))

    expect(await screen.findByText('Katalogseite')).toBeInTheDocument()
  })
})
