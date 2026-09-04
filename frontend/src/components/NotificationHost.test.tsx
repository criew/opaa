import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import NotificationHost from './NotificationHost'
import { notify, useNotificationStore } from '../stores/notificationStore'

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
})
