import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import Sidebar from './Sidebar'

describe('Sidebar', () => {
  it('renders navigation items', () => {
    renderWithProviders(<Sidebar />, { withRouter: true })
    expect(screen.getByText('Chat')).toBeInTheDocument()
    expect(screen.getByText('Documents')).toBeInTheDocument()
    expect(screen.getByText('Settings')).toBeInTheDocument()
  })

  it('renders OPAA branding', () => {
    renderWithProviders(<Sidebar />, { withRouter: true })
    expect(screen.getByText('OPAA')).toBeInTheDocument()
    expect(screen.getByText('AI Project Assistant')).toBeInTheDocument()
  })
})
