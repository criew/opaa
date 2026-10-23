import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import ConfirmHost from '@/shared/ui/feedback/ConfirmHost'
import { getSelfDisclosureSettings } from '../api/selfDisclosureApi'
import SelfDisclosureSettingsSection from './SelfDisclosureSettingsSection'

function renderSection() {
  return renderWithProviders(
    <>
      <SelfDisclosureSettingsSection />
      <ConfirmHost />
    </>,
  )
}

describe('SelfDisclosureSettingsSection', () => {
  it('switches the self-disclosure off after a confirmation and on again', async () => {
    const user = userEvent.setup()
    renderSection()

    const toggle = await screen.findByRole('switch', { name: 'Selbstauskunft anbieten' })
    expect(toggle).toBeChecked()
    await user.click(toggle)
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await user.click(await screen.findByRole('button', { name: 'Abschalten' }))

    await waitFor(async () => expect((await getSelfDisclosureSettings()).enabled).toBe(false))
    expect(screen.getByRole('switch', { name: 'Selbstauskunft anbieten' })).not.toBeChecked()

    await user.click(screen.getByRole('switch', { name: 'Selbstauskunft anbieten' }))
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(async () => expect((await getSelfDisclosureSettings()).enabled).toBe(true))
  })

  it('changes the daily limit within 1 to 10 and refuses other values', async () => {
    const user = userEvent.setup()
    renderSection()

    const field = await screen.findByLabelText(/Tagesgrenze/)
    await user.clear(field)
    await user.type(field, '11')
    expect(screen.getByText('Bitte eine ganze Zahl von 1 bis 10 eingeben.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeDisabled()

    await user.clear(field)
    await user.type(field, '3')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(async () => expect((await getSelfDisclosureSettings()).dailyLimit).toBe(3))
  })

  it('shows nothing about any person, in particular no last export', async () => {
    renderSection()

    await screen.findByRole('switch', { name: 'Selbstauskunft anbieten' })
    expect(screen.queryByText(/letzte[rn]? (Export|Download)/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/zuletzt/i)).not.toBeInTheDocument()
  })
})
