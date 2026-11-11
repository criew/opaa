import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import Typography from '@mui/material/Typography'
import AllInclusiveIcon from '@mui/icons-material/AllInclusive'
import type { MetadataFilter, MetadataFilterOptionsResponse } from '@/shared/api/types'
import { CHAT_MAX_WIDTH } from '@/shared/theme/theme'
import { tourAnchor } from '@/shared/ui/a11y/tourAnchor'
import type { SearchScope } from '../stores/chatSettingsPersistence'
import type { MetadataFilterScope } from '../stores/metadataFilterOptionsStore'
import MetadataFilterPopover from './MetadataFilterPopover'
import type { SelectedChatAction } from './useChatActions'
import type { LibraryChip } from './useSpaceLibraryScope'
import {
  formatFieldChipLabel,
  libraryFieldChipLabel,
  withoutFormatField,
  withoutLibraryField,
} from './metadataFilterText'
import LoadingState from '@/shared/ui/states/LoadingState'

interface ChatScopeChipBarProps {
  /** The action mark the next question carries, e.g. a prompt; null without one. */
  selected: SelectedChatAction | null
  onClearSelected: () => void
  /** The agent chosen for the next question; it replaces the search scope of that question. */
  agentName?: string | null
  onClearAgent?: () => void
  /** Locks the whole input. */
  disabled: boolean
  scope: SearchScope
  /** Scope and filter stay as they are while an answer is pending. */
  scopeLocked: boolean
  libraryChips: LibraryChip[]
  onClearScope: () => void
  onSetScopeAll: () => void
  onRemoveLibrary: (libraryId: string) => void
  metadataEnabled: boolean
  metadataFilter: MetadataFilter | null
  filterOptions: MetadataFilterOptionsResponse | null
  filterScope: MetadataFilterScope
  onMetadataFilterChange: (filter: MetadataFilter | null) => void
  documentTypeChipLabel: string | undefined
  dateChip: string | undefined
  onRemoveDocumentTypeFilter: () => void
  onRemoveDateFilter: () => void
}

/**
 * The bar above the input: the action mark, the search scope as chips and the chat's core-field
 * filter. The next question searches exactly what the bar shows.
 */
export default function ChatScopeChipBar({
  selected,
  onClearSelected,
  agentName = null,
  onClearAgent,
  disabled,
  scope,
  scopeLocked,
  libraryChips,
  onClearScope,
  onSetScopeAll,
  onRemoveLibrary,
  metadataEnabled,
  metadataFilter,
  filterOptions,
  filterScope,
  onMetadataFilterChange,
  documentTypeChipLabel,
  dateChip,
  onRemoveDocumentTypeFilter,
  onRemoveDateFilter,
}: ChatScopeChipBarProps) {
  return (
    <Box
      sx={{
        maxWidth: CHAT_MAX_WIDTH,
        mx: 'auto',
        mb: 1,
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: 0.75,
      }}
    >
      {/* Set apart from the knowledge chips by icon, wording and square shape, not by colour
          alone: an agent works on the question, a knowledge chip only narrows the search. */}
      {agentName && (
        <Chip
          icon={<SmartToyOutlinedIcon />}
          label={`Agent: ${agentName}`}
          size="small"
          variant="outlined"
          color="secondary"
          onDelete={disabled ? undefined : onClearAgent}
          aria-label={`Agent ${agentName} entfernen`}
          data-testid="chosen-agent-chip"
          sx={{ borderRadius: 1 }}
        />
      )}
      {selected && (
        <Chip
          icon={<selected.source.Icon />}
          label={selected.source.markLabel(selected.mark)}
          size="small"
          variant="outlined"
          onDelete={disabled ? undefined : onClearSelected}
          aria-label={`Kennzeichnung ${selected.source.markLabel(selected.mark)} entfernen`}
          data-testid="used-prompt-chip"
        />
      )}
      {scope === 'all' && (
        <Chip
          icon={<AllInclusiveIcon />}
          label="@Space-Wissen"
          {...tourAnchor('space-knowledge')}
          size="small"
          color="primary"
          onDelete={scopeLocked ? undefined : onClearScope}
          // The default delete icon carries aria-hidden from MUI, so the accessible name has to
          // sit on the chip itself rather than on that icon.
          aria-label="Referenz Space-Wissen entfernen"
        />
      )}
      {scope === 'libraries' &&
        libraryChips.map((chip, position) => {
          // The quick tour explains the scope at its first chip, whichever form the scope has.
          const anchor = position === 0 ? tourAnchor('space-knowledge') : {}
          if (chip.kind === 'known') {
            return (
              <Chip
                key={chip.libraryId}
                {...anchor}
                label={chip.library.name}
                size="small"
                variant="filled"
                color="primary"
                onDelete={scopeLocked ? undefined : () => onRemoveLibrary(chip.libraryId)}
                aria-label={`Bibliotheksreferenz ${chip.library.name} entfernen`}
              />
            )
          }
          if (chip.kind === 'loading') {
            return (
              <Chip
                key={chip.libraryId}
                {...anchor}
                label={<LoadingState shape="inline" />}
                size="small"
                variant="outlined"
                disabled
              />
            )
          }
          return (
            <Chip
              key={chip.libraryId}
              {...anchor}
              label="Nicht verfügbare Bibliothek"
              size="small"
              variant="outlined"
              onDelete={scopeLocked ? undefined : () => onRemoveLibrary(chip.libraryId)}
              aria-label="Nicht verfügbare Bibliotheksreferenz entfernen"
            />
          )
        })}
      {scope === 'none' && (
        <>
          {/* The statement is a warning in itself; it needs no signal colour in the text
           (design guidelines 2.3). */}
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Antwortet ohne Dokumente.
          </Typography>
          <Chip
            icon={<AllInclusiveIcon />}
            label="@Space-Wissen nutzen"
            {...tourAnchor('space-knowledge')}
            size="small"
            variant="outlined"
            onClick={scopeLocked ? undefined : onSetScopeAll}
            disabled={scopeLocked}
            // Named by its visible label (WCAG 2.5.3); what it does is the description.
            title="Wieder das Wissen des Space durchsuchen"
          />
        </>
      )}
      {scope !== 'none' && metadataEnabled && (
        <>
          {documentTypeChipLabel && (
            <Chip
              label={documentTypeChipLabel}
              size="small"
              variant="outlined"
              color="secondary"
              onDelete={scopeLocked ? undefined : onRemoveDocumentTypeFilter}
              aria-label={`${documentTypeChipLabel}: Filter entfernen`}
              data-testid="metadata-filter-chip-document-type"
            />
          )}
          {dateChip && (
            <Chip
              label={dateChip}
              size="small"
              variant="outlined"
              color="secondary"
              onDelete={scopeLocked ? undefined : onRemoveDateFilter}
              aria-label={`${dateChip}: Filter entfernen`}
              data-testid="metadata-filter-chip-document-date"
            />
          )}
          {(metadataFilter?.libraryFields ?? []).map((condition) => (
            <Chip
              key={`${condition.libraryId}/${condition.fieldKey}`}
              label={libraryFieldChipLabel(condition, filterOptions)}
              size="small"
              variant="outlined"
              color="secondary"
              onDelete={
                scopeLocked
                  ? undefined
                  : () => {
                      if (metadataFilter)
                        onMetadataFilterChange(withoutLibraryField(metadataFilter, condition))
                    }
              }
              aria-label={`${libraryFieldChipLabel(condition, filterOptions)}: Filter entfernen`}
              data-testid="metadata-filter-chip-library-field"
            />
          ))}
          {(metadataFilter?.formatFields ?? []).map((condition) => (
            <Chip
              key={condition.fieldKey}
              label={formatFieldChipLabel(condition, filterOptions)}
              size="small"
              variant="outlined"
              color="secondary"
              onDelete={
                scopeLocked
                  ? undefined
                  : () => {
                      if (metadataFilter)
                        onMetadataFilterChange(withoutFormatField(metadataFilter, condition))
                    }
              }
              aria-label={`${formatFieldChipLabel(condition, filterOptions)}: Filter entfernen`}
              data-testid="metadata-filter-chip-format-field"
            />
          ))}
          <MetadataFilterPopover
            scope={filterScope}
            filter={metadataFilter}
            onChange={onMetadataFilterChange}
            disabled={scopeLocked}
          />
        </>
      )}
    </Box>
  )
}
