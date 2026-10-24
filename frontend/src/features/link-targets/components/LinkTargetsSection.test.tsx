import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '@/test/test-utils'
import { useLinkTargetsStore } from '@/shared/linkTargets/linkTargetsStore'
import { resetMockLinkTargets } from '../mocks/linkTargetHandlers'
import LinkTargetsSection from './LinkTargetsSection'

describe('LinkTargetsSection', () => {
  afterEach(() => {
    resetMockLinkTargets()
    useLinkTargetsStore.setState({ allowedOrigins: [], status: 'idle' })
  })

  it('says that only the own address is clickable while the list is empty', async () => {
    renderWithProviders(<LinkTargetsSection />)

    expect(await screen.findByTestId('link-targets-empty')).toHaveTextContent(
      'klickbar sind nur Links zur eigenen Adresse',
    )
  })

  it('adds an origin, shows it in the list and hands it to the renderer', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LinkTargetsSection />)

    await user.type(
      await screen.findByLabelText('Neues Linkziel'),
      'https://Intranet.Stadt.Example/',
    )
    await user.click(screen.getByRole('button', { name: 'Hinzufügen' }))

    const list = await screen.findByRole('list', { name: 'Erlaubte Linkziele' })
    expect(within(list).getByText('https://intranet.stadt.example')).toBeInTheDocument()
    expect(useLinkTargetsStore.getState().allowedOrigins).toEqual([
      'https://intranet.stadt.example',
    ])
    expect(screen.getByLabelText('Neues Linkziel')).toHaveValue('')
  })

  it('removes an origin again', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LinkTargetsSection />)
    await user.type(await screen.findByLabelText('Neues Linkziel'), 'https://a.example')
    await user.click(screen.getByRole('button', { name: 'Hinzufügen' }))

    await user.click(
      await screen.findByRole('button', { name: 'Linkziel https://a.example entfernen' }),
    )

    expect(await screen.findByTestId('link-targets-empty')).toBeInTheDocument()
  })

  it('shows the German message of a refused entry and keeps the draft', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LinkTargetsSection />)

    await user.type(await screen.findByLabelText('Neues Linkziel'), 'https://a.example/pfad')
    await user.click(screen.getByRole('button', { name: 'Hinzufügen' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Ungültiges Linkziel')
    expect(screen.getByLabelText('Neues Linkziel')).toHaveValue('https://a.example/pfad')
  })
})
