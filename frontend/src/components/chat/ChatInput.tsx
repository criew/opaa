import type { KeyboardEvent, MouseEvent } from 'react'
import { useEffect, useMemo, useRef, useState } from 'react'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import IconButton from '@mui/material/IconButton'
import Popover from '@mui/material/Popover'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FilterListIcon from '@mui/icons-material/FilterList'
import SendIcon from '@mui/icons-material/Send'
import { CHAT_MAX_WIDTH } from '../../theme/theme'
import type { SpaceListResponse } from '../../types/api'
import { spaceRoleLabel } from '../../utils/labels'

interface ChatInputProps {
  onSend: (message: string) => void
  disabled?: boolean
  spaces?: SpaceListResponse[]
  selectedSpaceIds?: string[]
  onSpaceFilterChange?: (spaceIds: string[]) => void
}

export default function ChatInput({
  onSend,
  disabled = false,
  spaces = [],
  selectedSpaceIds = [],
  onSpaceFilterChange,
}: ChatInputProps) {
  const [value, setValue] = useState('')
  const [filterAnchorEl, setFilterAnchorEl] = useState<HTMLElement | null>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const wasDisabled = useRef(false)

  useEffect(() => {
    if (wasDisabled.current && !disabled) {
      inputRef.current?.focus()
    }
    wasDisabled.current = disabled
  }, [disabled])

  const selectedSpaces = useMemo(
    () => spaces.filter((space) => selectedSpaceIds.includes(space.id)),
    [selectedSpaceIds, spaces],
  )

  const filterSummary =
    selectedSpaces.length === 0 ? 'Alle Spaces' : `${selectedSpaces.length} ausgewählt`

  const handleSend = () => {
    const trimmed = value.trim()
    if (!trimmed) return
    onSend(trimmed)
    setValue('')
  }

  const handleKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const handleOpenFilter = (event: MouseEvent<HTMLElement>) => {
    setFilterAnchorEl(event.currentTarget)
  }

  const handleCloseFilter = () => {
    setFilterAnchorEl(null)
  }

  return (
    <Box sx={{ flexShrink: 0, p: 2, bgcolor: 'background.default' }}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-end',
          gap: 1,
          maxWidth: CHAT_MAX_WIDTH,
          mx: 'auto',
          bgcolor: 'background.paper',
          border: 1,
          borderColor: 'divider',
          borderRadius: '16px',
          p: 1,
        }}
      >
        <TextField
          fullWidth
          multiline
          maxRows={6}
          placeholder="Stellen Sie eine Frage …"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          inputRef={inputRef}
          variant="standard"
          sx={{
            '& .MuiInputBase-root': {
              borderRadius: 0,
              background: 'none',
              px: 1.5,
              py: 1,
              alignItems: 'flex-start',
              '&::before, &::after': { display: 'none' },
            },
            '& textarea': {
              overflowY: 'auto !important',
              resize: 'none',
            },
          }}
        />
        <IconButton
          color="primary"
          onClick={handleSend}
          disabled={disabled || !value.trim()}
          aria-label="Nachricht senden"
          sx={{
            bgcolor: 'primary.main',
            color: 'white',
            '&:hover': { bgcolor: 'primary.dark' },
            '&.Mui-disabled': { bgcolor: 'action.disabledBackground' },
            borderRadius: '12px',
          }}
        >
          <SendIcon />
        </IconButton>
      </Box>

      <Box
        sx={{
          maxWidth: CHAT_MAX_WIDTH,
          mx: 'auto',
          mt: 0.75,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <Button
          size="small"
          startIcon={<FilterListIcon />}
          onClick={handleOpenFilter}
          disabled={disabled}
          sx={{ textTransform: 'none' }}
        >
          Suchbereich: {filterSummary}
        </Button>
        <Typography variant="caption" color="text.secondary">
          Enter zum Senden, Umschalt+Enter für eine neue Zeile
        </Typography>
      </Box>

      <Popover
        open={Boolean(filterAnchorEl)}
        anchorEl={filterAnchorEl}
        onClose={handleCloseFilter}
        anchorOrigin={{ vertical: 'top', horizontal: 'left' }}
        transformOrigin={{ vertical: 'bottom', horizontal: 'left' }}
      >
        <Box sx={{ p: 2, width: 360 }}>
          <Autocomplete
            multiple
            options={spaces}
            disableCloseOnSelect
            getOptionLabel={(option) => option.name}
            value={selectedSpaces}
            onChange={(_, next) => onSpaceFilterChange?.(next.map((space) => space.id))}
            renderOption={(props, option, { selected }) => {
              const { key, ...optionProps } = props
              return (
                <li key={key} {...optionProps}>
                  <Checkbox checked={selected} sx={{ mr: 1 }} />
                  <Box>
                    <Typography variant="body2">{option.name}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {spaceRoleLabel(option.userRole)}
                    </Typography>
                  </Box>
                </li>
              )
            }}
            renderInput={(params) => <TextField {...params} label="Spaces auswählen" />}
          />
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 1.5 }}>
            <Button size="small" onClick={() => onSpaceFilterChange?.([])}>
              Zurücksetzen
            </Button>
            <Button size="small" variant="contained" onClick={handleCloseFilter}>
              Fertig
            </Button>
          </Box>
        </Box>
      </Popover>
    </Box>
  )
}
