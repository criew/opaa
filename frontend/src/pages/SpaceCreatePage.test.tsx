import { describe, expect, it, beforeEach, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../test/test-utils'
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
    expect(screen.getByText('3 · Zusammenfassung')).toBeInTheDocument()

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

    expect(screen.getByText('Widerspruchsstelle')).toBeInTheDocument()
    expect(screen.getByText('Referat 12')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Space anlegen' }))

    expect(mockCreateNewSpace).toHaveBeenCalledWith('Widerspruchsstelle', 'Referat 12', 'PRIVATE')
    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-neu')
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
