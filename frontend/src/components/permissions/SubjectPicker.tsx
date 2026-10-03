import { useState } from 'react'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import CorporateFareIcon from '@mui/icons-material/CorporateFare'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined'
import PublicOutlinedIcon from '@mui/icons-material/PublicOutlined'
import type { SelectableGroupResponse, UserSummary } from '../../types/api'
import { useGroupSearch } from '../../hooks/useGroupSearch'
import { useUserSearch } from '../../hooks/useUserSearch'
import { allAccountsLabel } from '../../utils/labels'
import {
  PROTECTED_GROUP_SEARCH_HINT,
  emptySubjectSelection,
  groupDetailLine,
  groupLabel,
  type SubjectSelection,
} from './subjectSelection'

interface SubjectPickerProps {
  value: SubjectSelection
  onChange: (value: SubjectSelection) => void
  /** The accessible name of the search field. */
  ariaLabel?: string
  /** Persons no longer eligible here. */
  excludedUserIds?: string[]
  /** Groups no longer eligible here. */
  excludedGroupIds?: string[]
  /** Offers "Alle Konten" as an entry of its own; memberships and ownership do not know it. */
  allowAllAccounts?: boolean
}

type SubjectOption =
  | { kind: 'USER'; user: UserSummary }
  | { kind: 'GROUP'; group: SelectableGroupResponse }
  | { kind: 'ALL_ACCOUNTS' }

const ALL_ACCOUNTS_OPTION: SubjectOption = { kind: 'ALL_ACCOUNTS' }

function userLabel(user: UserSummary): string {
  if (user.displayName) return `${user.displayName} (${user.email ?? user.id})`
  return user.email ?? user.id
}

function optionLabel(option: SubjectOption): string {
  if (option.kind === 'USER') return userLabel(option.user)
  if (option.kind === 'GROUP') return `${groupLabel(option.group)} · Gruppe`
  return allAccountsLabel
}

function optionKey(option: SubjectOption): string {
  if (option.kind === 'USER') return `USER:${option.user.id}`
  if (option.kind === 'GROUP') return `GROUP:${option.group.id}`
  return 'ALL_ACCOUNTS'
}

/** 0 = equal, 1 = prefix, 2 = word prefix, 3 = contained, 4 = no textual match. */
function matchRank(text: string, query: string): number {
  const haystack = text.toLocaleLowerCase('de')
  const needle = query.trim().toLocaleLowerCase('de')
  if (!needle) return 4
  if (haystack === needle) return 0
  if (haystack.startsWith(needle)) return 1
  if (haystack.split(/[\s\-/.,()@]+/).some((word) => word.startsWith(needle))) return 2
  return haystack.includes(needle) ? 3 : 4
}

function rankOf(option: SubjectOption, query: string): number {
  if (option.kind === 'USER') {
    const { displayName, email } = option.user
    return Math.min(matchRank(displayName ?? '', query), matchRank(email ?? '', query))
  }
  if (option.kind === 'GROUP') {
    // The service returns a protected group only for its complete name, i.e. an exact match.
    if (option.group.name == null) return 0
    return matchRank(option.group.name, query)
  }
  return matchRank(allAccountsLabel, query)
}

/** Persons and groups in one list, closest match first, ties by label. */
function rankSubjectOptions(options: SubjectOption[], query: string): SubjectOption[] {
  return options
    .map((option) => ({ option, rank: rankOf(option, query), label: optionLabel(option) }))
    .sort((a, b) => a.rank - b.rank || a.label.localeCompare(b.label, 'de'))
    .map(({ option }) => option)
}

function selectedOption(value: SubjectSelection): SubjectOption | null {
  if (value.type === 'ALL_ACCOUNTS') return ALL_ACCOUNTS_OPTION
  if (value.type === 'USER' && value.user) return { kind: 'USER', user: value.user }
  if (value.type === 'GROUP' && value.group) return { kind: 'GROUP', group: value.group }
  return null
}

function toSelection(option: SubjectOption | null): SubjectSelection {
  if (!option) return emptySubjectSelection
  if (option.kind === 'USER') return { type: 'USER', user: option.user, group: null }
  if (option.kind === 'GROUP') return { type: 'GROUP', user: null, group: option.group }
  return { type: 'ALL_ACCOUNTS', user: null, group: null }
}

function OptionIcon({ option }: { option: SubjectOption }) {
  const sx = { mt: 0.25, flex: 'none' }
  if (option.kind === 'USER') return <PersonOutlineIcon fontSize="small" color="action" sx={sx} />
  if (option.kind === 'ALL_ACCOUNTS') {
    return <PublicOutlinedIcon fontSize="small" color="action" sx={sx} />
  }
  if (option.group.provider?.external) {
    return (
      <CorporateFareIcon
        fontSize="small"
        color="warning"
        titleAccess="Gruppe eines externen Anbieters"
        sx={sx}
      />
    )
  }
  return <GroupsOutlinedIcon fontSize="small" color="action" sx={sx} />
}

function OptionText({ option }: { option: SubjectOption }) {
  if (option.kind === 'USER') {
    return (
      <Stack spacing={0}>
        <Typography sx={{ fontSize: 13.5 }}>
          {option.user.displayName ?? option.user.email ?? option.user.id}
        </Typography>
        {option.user.displayName && option.user.email && (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {option.user.email}
          </Typography>
        )}
      </Stack>
    )
  }
  if (option.kind === 'GROUP') {
    return (
      <Stack spacing={0}>
        <Typography sx={{ fontSize: 13.5 }}>{groupLabel(option.group)} · Gruppe</Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {groupDetailLine(option.group)}
        </Typography>
      </Stack>
    )
  }
  return (
    <Stack spacing={0}>
      <Typography sx={{ fontSize: 13.5 }}>{allAccountsLabel}</Typography>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        Jede Person Ihrer Organisation
      </Typography>
    </Stack>
  )
}

/**
 * The shared recipient picker of grants, space memberships and ownership: one field searching
 * persons and groups server-side, results mixed by closeness. Which groups appear is the service's
 * decision; this picker is a convenience, never the enforcement.
 */
export default function SubjectPicker({
  value,
  onChange,
  ariaLabel = 'Person oder Gruppe suchen',
  excludedUserIds = [],
  excludedGroupIds = [],
  allowAllAccounts = false,
}: SubjectPickerProps) {
  const userSearch = useUserSearch()
  const groupSearch = useGroupSearch()
  // What the field shows is not always what is searched for: after a choice it shows the chosen
  // label, which must not become a new request.
  const [inputText, setInputText] = useState('')
  const query = userSearch.query

  const found: SubjectOption[] = [
    ...userSearch.users
      .filter((user) => !excludedUserIds.includes(user.id))
      .map((user) => ({ kind: 'USER' as const, user })),
    ...groupSearch.groups
      .filter((group) => !excludedGroupIds.includes(group.id))
      .map((group) => ({ kind: 'GROUP' as const, group })),
  ]
  // "Alle Konten" only where the input is empty or names it - otherwise it would be the one
  // option left whenever nothing matches, and the empty-result hint would never appear.
  const offersAllAccounts =
    allowAllAccounts && (query.trim() === '' || matchRank(allAccountsLabel, query) < 4)
  const options = rankSubjectOptions(
    offersAllAccounts ? [ALL_ACCOUNTS_OPTION, ...found] : found,
    query,
  )
  const error = userSearch.error ?? groupSearch.error
  const tooShort = query.trim().length < 2

  return (
    <Autocomplete<SubjectOption>
      options={options}
      filterOptions={(option) => option}
      getOptionLabel={optionLabel}
      getOptionKey={optionKey}
      getOptionDisabled={(option) => option.kind === 'GROUP' && !option.group.selectable}
      isOptionEqualToValue={(option, selected) => optionKey(option) === optionKey(selected)}
      loading={userSearch.isLoading || groupSearch.isLoading}
      loadingText="Suche läuft …"
      noOptionsText={
        error
          ? 'Die Suche ist fehlgeschlagen.'
          : tooShort
            ? 'Mindestens zwei Zeichen eingeben'
            : `Keine Treffer. ${PROTECTED_GROUP_SEARCH_HINT}`
      }
      value={selectedOption(value)}
      onChange={(_event, next) => onChange(toSelection(next))}
      inputValue={inputText}
      onInputChange={(_event, next, reason) => {
        // Only typed text is a new request (#778): 'selectOption' and 'reset' set the text to the
        // chosen label, 'clear' empties both.
        setInputText(next)
        if (reason === 'input') {
          userSearch.setQuery(next)
          groupSearch.setQuery(next)
        } else if (reason === 'clear') {
          userSearch.setQuery('')
          groupSearch.setQuery('')
        }
      }}
      renderOption={(props, option) => {
        const { key, ...optionProps } = props as typeof props & { key: string }
        return (
          <Box component="li" key={key} {...optionProps}>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start', width: '100%' }}>
              <OptionIcon option={option} />
              <OptionText option={option} />
            </Stack>
          </Box>
        )
      }}
      renderInput={(params) => (
        <TextField
          {...params}
          placeholder="Person oder Gruppe suchen …"
          // A failed half of the search stays visible even when the other half found something.
          error={Boolean(error)}
          helperText={error ?? undefined}
          slotProps={{
            ...params.slotProps,
            htmlInput: { ...params.slotProps.htmlInput, 'aria-label': ariaLabel },
          }}
        />
      )}
      size="small"
      fullWidth
    />
  )
}
