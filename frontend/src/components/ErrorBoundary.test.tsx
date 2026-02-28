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

    expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    expect(
      screen.getByText('An unexpected error occurred. Please try reloading the page.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Show Details' })).toBeInTheDocument()
  })

  it('shows error details when "Show Details" is clicked', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const user = userEvent.setup()

    render(
      <ErrorBoundary>
        <ThrowingComponent message="Something broke" />
      </ErrorBoundary>,
    )

    await user.click(screen.getByRole('button', { name: 'Show Details' }))

    expect(screen.getByText('Something broke')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Hide Details' })).toBeInTheDocument()
  })

  it('hides error details when "Hide Details" is clicked', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const user = userEvent.setup()

    render(
      <ErrorBoundary>
        <ThrowingComponent message="Another error" />
      </ErrorBoundary>,
    )

    await user.click(screen.getByRole('button', { name: 'Show Details' }))
    expect(screen.getByRole('button', { name: 'Hide Details' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Hide Details' }))
    expect(screen.getByRole('button', { name: 'Show Details' })).toBeInTheDocument()
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
