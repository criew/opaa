import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import DateSeparator from './DateSeparator'

describe('DateSeparator', () => {
  it('renders a formatted date', () => {
    const date = new Date('2026-02-19T12:00:00Z')
    render(<DateSeparator date={date} />)
    expect(screen.getByText(/2026/)).toBeInTheDocument()
  })
})
