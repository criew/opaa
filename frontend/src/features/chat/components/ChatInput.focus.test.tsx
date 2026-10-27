import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import ChatInput from './ChatInput'
import { createAppTheme } from '@/shared/theme/theme'
import { darkRoles, lightRoles } from '@/shared/theme/tokens'
import { contrastRatio, TEXT_CONTRAST_MINIMUM } from '@/shared/utils/contrast'

const MAX_TAB_PRESSES = 20

/** `#0B6FBC` → `rgb(11, 111, 188)`: jsdom returns computed colours in this form. */
function asRgb(hex: string): string {
  const value = Number.parseInt(hex.replace('#', ''), 16)
  return `rgb(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255})`
}

/**
 * The message field is a borderless standard input inside a framed row; the frame is what a
 * keyboard user sees, so it carries the focus like an outlined field: 2px in accent (guidelines
 * 4.4 and 5.2).
 */
describe.each([
  ['light', lightRoles],
  ['dark', darkRoles],
] as const)('the message frame under keyboard focus (%s)', (mode, roles) => {
  it('turns into a 2px accent frame', async () => {
    render(
      <ThemeProvider theme={createAppTheme(mode)}>
        <MemoryRouter>
          <ChatInput onSend={vi.fn()} />
        </MemoryRouter>
      </ThemeProvider>,
    )
    const input = screen.getByPlaceholderText('Nachricht eingeben …')
    const frame = input.closest('.MuiTextField-root')?.parentElement as HTMLElement
    expect(getComputedStyle(frame).borderColor).not.toBe(asRgb(roles.accent))

    // Reached with Tab, past whatever precedes the field in the row.
    for (
      let presses = 0;
      presses < MAX_TAB_PRESSES && document.activeElement !== input;
      presses++
    ) {
      await userEvent.tab()
    }
    expect(input).toHaveFocus()

    const style = getComputedStyle(frame)
    // 1px border plus a 1px inset shadow: 2px of accent without moving the content.
    expect(style.borderColor).toBe(asRgb(roles.accent))
    expect(style.boxShadow.toLowerCase()).toBe(`inset 0 0 0 1px ${roles.accent.toLowerCase()}`)
    expect(contrastRatio(roles.accent, roles.bg1)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
  })

  it('keeps the resting frame while a button of the row holds the focus', async () => {
    render(
      <ThemeProvider theme={createAppTheme(mode)}>
        <MemoryRouter>
          <ChatInput onSend={vi.fn()} />
        </MemoryRouter>
      </ThemeProvider>,
    )
    const input = screen.getByPlaceholderText('Nachricht eingeben …')
    const frame = input.closest('.MuiTextField-root')?.parentElement as HTMLElement
    const resting = getComputedStyle(frame).borderColor
    // Text enables the send button, the one button of the row that is always rendered.
    await userEvent.type(input, 'Frage')
    const button = screen.getByRole('button', { name: 'Senden' })
    expect(frame).toContainElement(button)

    for (
      let presses = 0;
      presses < MAX_TAB_PRESSES && document.activeElement !== button;
      presses++
    ) {
      await userEvent.tab()
    }
    expect(button).toHaveFocus()

    // The button shows its own ring; a second indicator on the frame would mark two places.
    expect(getComputedStyle(frame).borderColor).toBe(resting)
  })
})
