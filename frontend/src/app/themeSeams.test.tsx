import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import { useChatListStore } from '@/features/chat'
import { loadSpaceList, renderSpaceChats } from './themeSeams'

describe('theme seams filled by the application frame', () => {
  beforeEach(() => {
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
  })

  it('renders the chat list of the given space into the space page', async () => {
    renderWithProviders(<>{renderSpaceChats('space-personal')}</>, { withRouter: true })

    expect(await screen.findByText('Architektur des Projekts')).toBeInTheDocument()
    expect(await screen.findByText('Deployment-Fragen')).toBeInTheDocument()
  })

  it('hands asset components the spaces of the signed-in person', async () => {
    const spaces = await loadSpaceList()

    expect(spaces.map((space) => space.name)).toEqual(
      expect.arrayContaining(['Privater Bereich', 'Engineering', 'Phoenix']),
    )
  })
})
