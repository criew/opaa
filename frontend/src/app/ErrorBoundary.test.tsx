import type React from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import ErrorBoundary from './ErrorBoundary'

function ThrowingComponent({ message }: { message: string }): React.ReactNode {
  throw new Error(message)
}

describe('ErrorBoundary', () => {
  it('renders children when no error occurs', () => {
    render(
      <ErrorBoundary>
        <div>App content</div>
      </ErrorBoundary>,
    )

    expect(screen.getByText('App content')).toBeInTheDocument()
  })

  it('renders error screen when a child throws', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})

    render(
      <ErrorBoundary>
        <ThrowingComponent message="Test crash" />
      </ErrorBoundary>,
    )

    expect(screen.getByText('Etwas ist schiefgelaufen')).toBeInTheDocument()
    expect(
      screen.getByText('Ein unerwarteter Fehler ist aufgetreten. Bitte laden Sie die Seite neu.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Neu laden' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Details anzeigen' })).toBeInTheDocument()
  })

  it('shows error details when "Show Details" is clicked', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const user = userEvent.setup()

    render(
      <ErrorBoundary>
        <ThrowingComponent message="Something broke" />
      </ErrorBoundary>,
    )

    await user.click(screen.getByRole('button', { name: 'Details anzeigen' }))

    expect(screen.getByText('Something broke')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Details ausblenden' })).toBeInTheDocument()
  })

  it('hides error details when "Hide Details" is clicked', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const user = userEvent.setup()

    render(
      <ErrorBoundary>
        <ThrowingComponent message="Another error" />
      </ErrorBoundary>,
    )

    await user.click(screen.getByRole('button', { name: 'Details anzeigen' }))
    expect(screen.getByRole('button', { name: 'Details ausblenden' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Details ausblenden' }))
    expect(screen.getByRole('button', { name: 'Details anzeigen' })).toBeInTheDocument()
  })

  it('logs the error to console', () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    render(
      <ErrorBoundary>
        <ThrowingComponent message="Console test" />
      </ErrorBoundary>,
    )

    expect(consoleSpy).toHaveBeenCalledWith(
      'ErrorBoundary caught:',
      expect.any(Error),
      expect.objectContaining({ componentStack: expect.any(String) }),
    )
  })
})
