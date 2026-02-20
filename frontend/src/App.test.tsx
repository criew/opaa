import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('App', () => {
  it('renders the OPAA branding', () => {
    render(<App />)
    expect(screen.getAllByText('OPAA').length).toBeGreaterThan(0)
  })

  it('redirects to chat page by default', () => {
    render(<App />)
    expect(screen.getByText('How can I help you today?')).toBeInTheDocument()
  })

  it('renders navigation links', () => {
    render(<App />)
    expect(screen.getByText('Chat')).toBeInTheDocument()
    expect(screen.getByText('Documents')).toBeInTheDocument()
    expect(screen.getByText('Settings')).toBeInTheDocument()
  })
})
