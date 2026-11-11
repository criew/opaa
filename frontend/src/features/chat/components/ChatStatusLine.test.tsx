import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import ChatStatusLine from './ChatStatusLine'

describe('ChatStatusLine', () => {
  it('shows a spinner with its sentence in one polite status', () => {
    render(<ChatStatusLine text="Denkt nach …" />)

    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-live', 'polite')
    expect(status).toHaveTextContent('Denkt nach …')
    expect(screen.getByRole('progressbar', { hidden: true })).toHaveAttribute('aria-hidden')
  })

  it('keeps the same spinner when its text changes, so nothing below it moves', () => {
    const { rerender } = render(<ChatStatusLine text="Denkt nach …" />)
    const spinner = screen.getByRole('progressbar', { hidden: true })
    const style = spinner.getAttribute('style')

    rerender(<ChatStatusLine text="Antwort wird geschrieben …" />)

    expect(screen.getByRole('progressbar', { hidden: true })).toBe(spinner)
    expect(spinner.getAttribute('style')).toBe(style)
  })
})
