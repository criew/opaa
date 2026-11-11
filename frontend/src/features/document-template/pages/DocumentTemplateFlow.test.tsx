import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { renderWithProviders, setMockAuthState } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import { useRuntimeSwitchStore } from '@/shared/session/runtimeSwitches'
import { mockDocumentTemplateState } from '../mocks/documentTemplateFixtures'
import DocumentTemplateCreatePage from './DocumentTemplateCreatePage'
import DocumentTemplateDetailPage from './DocumentTemplateDetailPage'
import DocumentTemplateSettingsPage from './DocumentTemplateSettingsPage'

function renderTemplates(initialRoute: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/templates/new" element={<DocumentTemplateCreatePage />} />
      <Route path="/templates/:documentTemplateId" element={<DocumentTemplateDetailPage />} />
      <Route path="/templates/:documentTemplateId/:tab" element={<DocumentTemplateDetailPage />} />
    </Routes>,
    { withRouter: true, initialRoute },
  )
}

function holdCapabilities(capabilities: string[]) {
  server.use(http.get('/api/v1/me/capabilities', () => HttpResponse.json({ capabilities })))
}

function docx(name = 'vermerk.docx') {
  return new File(['PK'], name, {
    type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  })
}

describe('Dokumentvorlagen', () => {
  beforeEach(() => {
    setMockAuthState()
    mockDocumentTemplateState.settings = { enabled: true, updatedAt: null, updatedBy: null }
  })

  it('explains a missing Anlegerecht and leads „Anlegen“ to it instead of creating', async () => {
    holdCapabilities(['CREATE_SPACE'])
    let posted = false
    server.use(
      http.post('/api/v1/document-templates', () => {
        posted = true
        return HttpResponse.json({}, { status: 500 })
      }),
    )
    const user = userEvent.setup()
    renderTemplates('/templates/new')

    await user.type(screen.getByRole('textbox', { name: /Name/ }), 'Vermerk')
    await user.upload(screen.getByLabelText('Word-Datei auswählen'), docx())
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    const hint = await screen.findByText(/Ihnen fehlt das Anlegerecht „Vorlagen anlegen“/)
    expect(hint).toBeVisible()
    const create = screen.getByRole('button', { name: 'Anlegen' })
    expect(create).toBeEnabled()
    await user.click(create)
    expect(hint.closest('[role="alert"]')).toHaveFocus()
    expect(posted).toBe(false)
  })

  it('creates a template from a Word file and shows its placeholders', async () => {
    holdCapabilities(['CREATE_TEMPLATE'])
    // Node's multipart parser refuses jsdom's File, so the answer does not read the form.
    server.use(
      http.post('/api/v1/document-templates', () => {
        const created = {
          ...mockDocumentTemplateState.templates['document-template-vermerk'],
          id: 'document-template-neu',
          name: 'Vermerk Referat 12',
          placeholders: [{ name: 'betreff', kind: 'FIELD' as const, fields: [] }],
        }
        mockDocumentTemplateState.templates[created.id] = created
        return HttpResponse.json(created, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderTemplates('/templates/new')

    // „Weiter“ stays active and names both missing answers at their fields
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    expect(screen.getByRole('textbox', { name: /Name/ })).toHaveFocus()
    expect(screen.getByText('Name fehlt')).toBeVisible()
    expect(screen.getByText('Word-Datei fehlt')).toBeVisible()

    await user.type(screen.getByRole('textbox', { name: /Name/ }), 'Vermerk Referat 12')
    await user.upload(screen.getByLabelText('Word-Datei auswählen'), docx())
    expect(screen.getByText('vermerk.docx')).toBeVisible()
    expect(screen.queryByText('Word-Datei fehlt')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    expect(screen.getByRole('heading', { level: 2, name: 'Prüfen' })).toBeInTheDocument()
    expect(screen.getAllByText('vermerk.docx').length).toBeGreaterThan(0)
    await user.click(screen.getByRole('button', { name: 'Anlegen' }))

    expect(await screen.findByRole('cell', { name: /betreff/ })).toBeVisible()
    expect(
      Object.values(mockDocumentTemplateState.templates).some(
        (template) => template.name === 'Vermerk Referat 12',
      ),
    ).toBe(true)
  })

  it('leads a responsible unit the server refuses back to its field under „Freigaben“', async () => {
    holdCapabilities(['CREATE_TEMPLATE'])
    server.use(
      http.post('/api/v1/document-templates', () =>
        HttpResponse.json(
          {
            error: 'Bitte prüfen Sie die markierten Angaben.',
            fieldErrors: [
              { field: 'responsible', code: 'UNKNOWN', message: 'Diese Gruppe gibt es nicht.' },
            ],
          },
          { status: 400 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderTemplates('/templates/new')

    await user.type(screen.getByRole('textbox', { name: /Name/ }), 'Vermerk')
    await user.upload(screen.getByLabelText('Word-Datei auswählen'), docx())
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Anlegen' }))

    expect(await screen.findByRole('heading', { level: 2, name: 'Freigaben' })).toBeVisible()
    const field = screen.getByRole('combobox', { name: 'Verantwortliche Stelle' })
    await waitFor(() => expect(field).toHaveFocus())
    expect(field).toHaveAccessibleDescription('Diese Gruppe gibt es nicht.')
  })

  it('lists the placeholders of a template with their kind and the parts of a list', async () => {
    renderTemplates('/templates/document-template-vermerk')

    expect(await screen.findByRole('cell', { name: /massnahmen/ })).toBeVisible()
    expect(screen.getByRole('cell', { name: 'Liste' })).toBeVisible()
    expect(screen.getByRole('cell', { name: 'titel, frist' })).toBeVisible()
    expect(screen.getByRole('cell', { name: 'Bedingung' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Neue Word-Datei auswählen' })).toBeVisible()
  })

  it('says so when templates are switched off', async () => {
    mockDocumentTemplateState.settings = { enabled: false, updatedAt: null, updatedBy: null }
    renderTemplates('/templates/document-template-vermerk')

    expect(await screen.findByRole('alert')).toHaveTextContent('Objekt nicht gefunden')
  })
})

describe('DocumentTemplateSettingsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({
      mode: 'dev',
      isAuthenticated: true,
      isLoading: false,
      user: {
        id: 'admin-1',
        email: 'admin@opaa.local',
        displayName: 'Admin',
        systemRole: 'SYSTEM_ADMIN',
      },
      token: null,
      error: null,
      userManager: null,
    })
  })

  it('switches templates on for the whole installation, off as delivered', async () => {
    const user = userEvent.setup()
    renderWithProviders(<DocumentTemplateSettingsPage />)

    const toggle = await screen.findByRole('switch', { name: 'Vorlagen anbieten' })
    expect(toggle).not.toBeChecked()
    await user.click(toggle)

    await waitFor(() => expect(toggle).toBeChecked())
    expect(mockDocumentTemplateState.settings.enabled).toBe(true)
    await waitFor(() => expect(useRuntimeSwitchStore.getState().documentTemplates).toBe(true))
    expect(screen.getByText(/Zuletzt geändert am/)).toBeVisible()
  })
})
