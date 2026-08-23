import { describe, expect, it, beforeEach, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import SpaceCreatePage from './SpaceCreatePage'
import { useSpaceStore } from '../stores/spaceStore'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return { ...actual, useNavigate: () => mockNavigate }
})

const mockCreateNewSpace = vi.fn(async () => 'space-neu')
const mockAddMember = vi.fn(async () => {})

describe('SpaceCreatePage (#594, Mockup 1b)', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    mockCreateNewSpace.mockClear()
    mockAddMember.mockClear()
    useSpaceStore.setState({
      createNewSpace: mockCreateNewSpace,
      addMember: mockAddMember,
    })
  })

  it('renders the stepper and blocks "Weiter" until a name is entered', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    expect(screen.getByRole('heading', { level: 1, name: 'Neuer Space' })).toBeInTheDocument()
    expect(screen.getByText('1 · Grunddaten')).toBeInTheDocument()
    expect(screen.getByText('2 · Mitglieder')).toBeInTheDocument()
    expect(screen.getByText('3 · Datenquellen')).toBeInTheDocument()
    expect(screen.getByText('4 · Zusammenfassung')).toBeInTheDocument()

    expect(screen.getByRole('button', { name: 'Weiter' })).toBeDisabled()
    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    expect(screen.getByRole('button', { name: 'Weiter' })).toBeEnabled()
  })

  it('keeps entered values when going back a step', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    expect(screen.getByText(/Mitglieder lassen sich auch später/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    expect(screen.getByLabelText(/Name/)).toHaveValue('Widerspruchsstelle')
  })

  it('shows the summary and creates the space with its members', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.type(screen.getByLabelText(/Beschreibung/), 'Referat 12')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    expect(screen.getByText('Widerspruchsstelle')).toBeInTheDocument()
    expect(screen.getByText('Referat 12')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Space anlegen' }))

    expect(mockCreateNewSpace).toHaveBeenCalledWith(
      'Widerspruchsstelle',
      'Referat 12',
      'PRIVATE',
      [],
    )
    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-neu')
  })

  it('#777: offers the user picker on the Mitglieder step, powered by GET /v1/users', async () => {
    // #778 review, finding 2: the shared /api/v1/admin/users MSW handler answers every request
    // unconditionally (it has to - handlers.ts must not import the auth store itself, see
    // src/test/setup.ts's own comment on why pulling the shared axios client in that early breaks
    // request interception for a dozen unrelated tests), so a plain assertion against the picker
    // options would stay green even if the picker regressed to calling GET /v1/admin/users
    // instead of GET /v1/users. This override makes that admin-only endpoint fail exactly as the
    // real backend's SYSTEM_ADMIN-gated AdminController#listUsers would for this caller, so the
    // guard is meaningful: reverting getUserSummaries() back to getUsers() empties the picker and
    // fails this test, instead of passing vacuously against an MSW mock that never enforced the
    // boundary #777 exists to work around.
    server.use(
      http.get('/api/v1/admin/users', () => {
        return HttpResponse.json({ error: 'Zugriff verweigert' }, { status: 403 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    // #778 review, finding 4: the picker no longer preloads the whole organization - a query
    // (min. 2 characters) has to be typed before GET /v1/users is even attempted.
    await user.type(screen.getByLabelText('Benutzer'), 'al')
    expect(await screen.findByRole('option', { name: /Alice/ })).toBeInTheDocument()
  })

  it('asks before cancelling once something was entered', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await user.type(screen.getByLabelText(/Name/), 'W')
    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))

    expect(confirmSpy).toHaveBeenCalled()
    expect(mockNavigate).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})
