import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChatInput from './ChatInput'
import { server } from '../../mocks/server'
import { useAuthStore } from '@/shared/session/authStore'
import { useChatStore } from '../../stores/chatStore'
import { useMetadataFilterOptionsStore } from '../../stores/metadataFilterOptionsStore'
import { useSpaceStore } from '@/features/space'

/**
 * ADR-0044: with metadata switched off the chat offers no filter and shows no stored one - a
 * chat that still carries a filter from before keeps it, but nothing of it appears.
 */
describe('ChatInput without metadata', () => {
  const requested: URL[] = []

  beforeEach(() => {
    requested.length = 0
    server.use(
      http.get('/api/v1/search/metadata-filter-options', ({ request }) => {
        requested.push(new URL(request.url))
        return HttpResponse.json({
          totalDocuments: 0,
          fields: [],
          documentTypes: [],
          libraryFields: [],
          formatFields: [],
        })
      }),
    )
    useAuthStore.setState({ features: { metadata: false } })
    useMetadataFilterOptionsStore.getState().reset()
    useSpaceStore.setState({
      spaces: [],
      assetAssociations: [],
      hasAssetAssociations: true,
      hasKnowledge: true,
      hasReadableKnowledge: true,
      isLoadingAssetAssociations: false,
      assetAssociationsSpaceId: null,
    })
    useChatStore.setState({
      chatId: null,
      spaceId: 'space-a',
      scope: 'all',
      referencedLibraryIds: [],
      metadataFilter: {
        documentTypes: ['VERMERK'],
        documentDateFrom: '2024-01-01',
        documentDateTo: '2024-12-31',
      },
    })
  })

  afterEach(() => {
    useAuthStore.setState({ features: {} })
  })

  it('shows neither the stored filter nor the button to set one', () => {
    render(<ChatInput onSend={vi.fn()} />)

    expect(screen.queryByTestId('metadata-filter-chip-document-type')).not.toBeInTheDocument()
    expect(screen.queryByTestId('metadata-filter-chip-document-date')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Metadatenfilter setzen' })).not.toBeInTheDocument()
  })

  it('asks the server for no filter options', async () => {
    render(<ChatInput onSend={vi.fn()} />)
    await useMetadataFilterOptionsStore.getState().loadOptions({
      chatId: null,
      spaceId: 'space-a',
      useKnowledge: true,
      libraryIds: [],
    })

    expect(requested).toHaveLength(0)
    expect(useMetadataFilterOptionsStore.getState().options).toBeNull()
  })
})
