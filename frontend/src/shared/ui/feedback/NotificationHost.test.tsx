import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import NotificationHost from './NotificationHost'
import { notify, useNotificationStore } from '../../stores/notificationStore'

describe('NotificationHost', () => {
  beforeEach(() => {
    useNotificationStore.getState().reset()
  })

  it('renders nothing while the queue is empty', () => {
    render(<NotificationHost />)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows the oldest notification first and moves on once it is dismissed', async () => {
    render(<NotificationHost />)
    const user = userEvent.setup()

    act(() => {
      notify('Das Original konnte nicht geöffnet werden.', 'error')
      notify('bescheid.docx wird heruntergeladen')
    })

    expect(await screen.findByText('Das Original konnte nicht geöffnet werden.')).toBeVisible()
    expect(screen.queryByText('bescheid.docx wird heruntergeladen')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /schließen|close/i }))

    expect(await screen.findByText('bescheid.docx wird heruntergeladen')).toBeVisible()
  })

  it('anchors every popup at the top center, queued ones included', async () => {
    render(<NotificationHost />)
    const user = userEvent.setup()

    act(() => {
      notify('Leistung zugeordnet.', 'success')
      notify('Rolle geändert.')
    })

    const first = await screen.findByRole('alert')
    expect(first.closest('.MuiSnackbar-root')).toHaveClass('MuiSnackbar-anchorOriginTopCenter')

    await user.click(within(first).getByRole('button', { name: /schließen|close/i }))

    const second = await screen.findByText('Rolle geändert.')
    expect(second.closest('.MuiSnackbar-root')).toHaveClass('MuiSnackbar-anchorOriginTopCenter')
  })

  it('starts the popup below the mobile header rather than over it', async () => {
    render(<NotificationHost />)
    act(() => {
      notify('Leistung zugeordnet.', 'success')
    })

    const root = (await screen.findByRole('alert')).closest('.MuiSnackbar-root') as HTMLElement
    // jsdom applies no media queries: the xs value is the toolbar's 56 px + 1 px border + 8 px
    expect(getComputedStyle(root).top).toBe('65px')
  })

  it('runs the action of a notification and dismisses it', async () => {
    render(<NotificationHost />)
    const user = userEvent.setup()
    const undo = vi.fn()

    act(() => {
      notify('„Dienstanweisungen“ gelöst.', 'info', { label: 'Rückgängig', onClick: undo })
    })

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByRole('button', { name: 'Schließen' })).toBeVisible()
    await user.click(within(alert).getByRole('button', { name: 'Rückgängig' }))

    expect(undo).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  describe('auto-dismiss (WCAG 2.2.1)', () => {
    beforeEach(() => {
      vi.useFakeTimers()
    })
    afterEach(() => {
      vi.useRealTimers()
    })

    function show() {
      render(<NotificationHost />)
      act(() => {
        notify('Gespeichert.')
      })
      return screen.getByRole('alert')
    }

    const advance = (ms: number) =>
      act(() => {
        vi.advanceTimersByTime(ms)
      })

    it('dismisses a popup after six seconds', () => {
      show()
      advance(5900)
      expect(screen.getByText('Gespeichert.')).toBeInTheDocument()
      advance(200)
      expect(screen.queryByText('Gespeichert.')).not.toBeInTheDocument()
    })

    it('stands still while the pointer is on the popup and runs again once it left', () => {
      const alert = show()
      fireEvent.mouseEnter(alert)
      advance(20000)
      expect(screen.getByText('Gespeichert.')).toBeInTheDocument()

      fireEvent.mouseLeave(alert)
      // the six seconds start afresh rather than resuming a shortened rest
      advance(5500)
      expect(screen.getByText('Gespeichert.')).toBeInTheDocument()
      advance(600)
      expect(screen.queryByText('Gespeichert.')).not.toBeInTheDocument()
    })

    it('stands still while the focus is in the popup and runs again once it left', () => {
      const alert = show()
      const close = within(alert).getByRole('button', { name: /schließen|close/i })
      act(() => close.focus())
      advance(20000)
      expect(screen.getByText('Gespeichert.')).toBeInTheDocument()

      act(() => close.blur())
      // the six seconds start afresh rather than resuming a shortened rest
      advance(5500)
      expect(screen.getByText('Gespeichert.')).toBeInTheDocument()
      advance(600)
      expect(screen.queryByText('Gespeichert.')).not.toBeInTheDocument()
    })
  })
})
