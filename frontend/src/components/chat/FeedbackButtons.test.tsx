import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import FeedbackButtons from './FeedbackButtons'

describe('FeedbackButtons', () => {
  it('renders thumbs up and down buttons', () => {
    render(<FeedbackButtons />)
    expect(screen.getByLabelText('thumbs up')).toBeInTheDocument()
    expect(screen.getByLabelText('thumbs down')).toBeInTheDocument()
  })

  it('toggles thumbs up on click', async () => {
    const user = userEvent.setup()
    render(<FeedbackButtons />)
    const button = screen.getByLabelText('thumbs up')

    // Initially no primary color
    expect(button).not.toHaveClass('MuiIconButton-colorPrimary')

    await user.click(button)
    expect(button).toHaveClass('MuiIconButton-colorPrimary')

    // Toggle off
    await user.click(button)
    expect(button).not.toHaveClass('MuiIconButton-colorPrimary')
  })
})
