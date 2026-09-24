import { beforeEach, describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { renderWithProviders, setMockAuthState } from '../test/test-utils'
import { usePromptLibraryStore } from '../stores/promptLibraryStore'
import PromptLibrariesPage from './PromptLibrariesPage'

describe('PromptLibrariesPage', () => {
  beforeEach(() => {
    setMockAuthState()
    usePromptLibraryStore.getState().reset()
    window.localStorage.removeItem('opaa.overview.prompt-libraries.view')
  })

  it('lists the readable prompt libraries in the shared overview', async () => {
    renderWithProviders(<PromptLibrariesPage />, { withRouter: true })

    expect(screen.getByRole('heading', { level: 1, name: 'Prompts' })).toBeInTheDocument()
    const card = await screen.findByRole('link', { name: /Formulierungshilfen Referat 50/ })
    expect(card).toHaveAttribute('href', '/prompts/prompt-library-referat-50')
    expect(card).toHaveTextContent('Referat 50 · 2 Prompts')
    expect(screen.getByText('2 Prompt-Bibliotheken')).toBeInTheDocument()
    expect(screen.getByText('In der Organisation geteilt')).toBeInTheDocument()
  })

  it('leads to the creation wizard', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route path="/prompts" element={<PromptLibrariesPage />} />
        <Route path="/prompts/new" element={<p>Assistent</p>} />
      </Routes>,
      { withRouter: true, initialRoute: '/prompts' },
    )

    await user.click(screen.getByRole('button', { name: 'Neue Prompt-Bibliothek' }))

    expect(await screen.findByText('Assistent')).toBeInTheDocument()
  })
})
