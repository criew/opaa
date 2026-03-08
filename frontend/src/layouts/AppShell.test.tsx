import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import AppShell from './AppShell'

describe('AppShell', () => {
  it('renders sidebar navigation links', () => {
    renderWithProviders(<AppShell />, { withRouter: true, initialRoute: '/chat' })
    expect(screen.getByText('Workspaces')).toBeInTheDocument()
    expect(screen.getByText('Chats')).toBeInTheDocument()
    expect(screen.getByText('Settings')).toBeInTheDocument()
  })

  it('renders OPAA branding', () => {
    renderWithProviders(<AppShell />, { withRouter: true, initialRoute: '/chat' })
    expect(screen.getAllByText('OPAA').length).toBeGreaterThan(0)
  })
})
