import { useEffect, useMemo } from 'react'
import type { MetadataFilter } from '@/shared/api/types'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import type { SearchScope } from '../stores/chatSettingsPersistence'
import {
  metadataFilterScopeKey,
  useMetadataFilterOptionsStore,
} from '../stores/metadataFilterOptionsStore'
import { dateChipLabel, withoutDateWindow, withoutDocumentTypes } from './metadataFilterText'

interface ChatMetadataFilterChipsOptions {
  chatId: string | null
  chatSpaceId: string | null
  scope: SearchScope
  referencedLibraryIds: string[]
  metadataFilter: MetadataFilter | null
  setMetadataFilter: (filter: MetadataFilter | null) => void
}

/**
 * The chat's core-field filter as the chip bar shows it: the scope its options are loaded for, the
 * chip labels and the removal of single conditions.
 */
export function useChatMetadataFilterChips({
  chatId,
  chatSpaceId,
  scope,
  referencedLibraryIds,
  metadataFilter,
  setMetadataFilter,
}: ChatMetadataFilterChipsOptions) {
  const filterOptions = useMetadataFilterOptionsStore((s) => s.options)

  // The scope the next question searches - the filter options are loaded for exactly this scope,
  // resolved server-side with the query's own rules: the chat's own settings, or for a chat not yet
  // created its space with the chip bar's settings.
  const filterScope = useMemo(
    () => ({
      chatId,
      spaceId: chatSpaceId,
      useKnowledge: scope === 'all',
      libraryIds: scope === 'libraries' ? referencedLibraryIds : [],
    }),
    [chatId, chatSpaceId, referencedLibraryIds, scope],
  )

  // A chat loaded with a Dokumentart condition needs the labels before the popover was ever
  // opened; loading the options for the current scope resolves them.
  // Without metadata (ADR-0044) the chat offers no filter and shows no stored one.
  const metadataEnabled = useFeatureEnabled('metadata')
  const filterOptionsScopeKey = useMetadataFilterOptionsStore((s) => s.optionsScopeKey)
  const loadFilterOptions = useMetadataFilterOptionsStore((s) => s.loadOptions)
  const filterScopeKey = metadataFilterScopeKey(filterScope)
  const hasTypeFilter = (metadataFilter?.documentTypes ?? []).length > 0
  useEffect(() => {
    if (metadataEnabled && hasTypeFilter && filterOptionsScopeKey !== filterScopeKey) {
      void loadFilterOptions(filterScope)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [metadataEnabled, hasTypeFilter, filterOptionsScopeKey, filterScopeKey])

  const documentTypeChipLabel = useMemo(() => {
    const codes = metadataFilter?.documentTypes ?? []
    if (codes.length === 0) return undefined
    const labels = codes.map(
      (code) => filterOptions?.documentTypes.find((type) => type.code === code)?.label ?? code,
    )
    return `Dokumentart: ${labels.join(', ')}`
  }, [filterOptions, metadataFilter])
  const dateChip = metadataFilter ? dateChipLabel(metadataFilter) : undefined

  const removeDocumentTypeFilter = () => {
    if (metadataFilter) setMetadataFilter(withoutDocumentTypes(metadataFilter))
  }
  const removeDateFilter = () => {
    if (metadataFilter) setMetadataFilter(withoutDateWindow(metadataFilter))
  }

  return {
    filterScope,
    metadataEnabled,
    filterOptions,
    documentTypeChipLabel,
    dateChip,
    removeDocumentTypeFilter,
    removeDateFilter,
  }
}
