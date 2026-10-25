import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import Dialog from '@mui/material/Dialog'
import DialogTitleWithClose from './DialogTitleWithClose'

describe('DialogTitleWithClose', () => {
  it('names the dialog and closes it with the ✕ „Schließen“', async () => {
    const onClose = vi.fn()
    render(
      <Dialog open onClose={onClose} aria-labelledby="t">
        <DialogTitleWithClose id="t" onClose={onClose}>
          Fehler · 3
        </DialogTitleWithClose>
      </Dialog>,
    )

    expect(screen.getByRole('dialog', { name: 'Fehler · 3' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Schließen' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
