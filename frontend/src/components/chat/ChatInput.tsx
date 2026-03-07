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
import type { WorkspaceListResponse } from '../../types/api'

interface ChatInputProps {
  onSend: (message: string) => void
  disabled?: boolean
  workspaces?: WorkspaceListResponse[]
  selectedWorkspaceIds?: string[]
  onWorkspaceFilterChange?: (workspaceIds: string[]) => void
}

export default function ChatInput({
  onSend,
  disabled = false,
  workspaces = [],
  selectedWorkspaceIds = [],
  onWorkspaceFilterChange,
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

  const selectedWorkspaces = useMemo(
    () => workspaces.filter((workspace) => selectedWorkspaceIds.includes(workspace.id)),
    [selectedWorkspaceIds, workspaces],
  )

  const filterSummary =
    selectedWorkspaces.length === 0 ? 'All Workspaces' : `${selectedWorkspaces.length} selected`

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
          placeholder="Ask a question..."
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
          aria-label="send message"
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
          Search scope: {filterSummary}
        </Button>
        <Typography variant="caption" color="text.secondary">
          Enter to send, Shift+Enter for new line
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
            options={workspaces}
            disableCloseOnSelect
            getOptionLabel={(option) => option.name}
            value={selectedWorkspaces}
            onChange={(_, next) => onWorkspaceFilterChange?.(next.map((workspace) => workspace.id))}
            renderOption={(props, option, { selected }) => {
              const { key, ...optionProps } = props
              return (
                <li key={key} {...optionProps}>
                  <Checkbox checked={selected} sx={{ mr: 1 }} />
                  <Box>
                    <Typography variant="body2">{option.name}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {option.userRole}
                    </Typography>
                  </Box>
                </li>
              )
            }}
            renderInput={(params) => <TextField {...params} label="Select workspaces" />}
          />
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 1.5 }}>
            <Button size="small" onClick={() => onWorkspaceFilterChange?.([])}>
              Clear
            </Button>
            <Button size="small" variant="contained" onClick={handleCloseFilter}>
              Done
            </Button>
          </Box>
        </Box>
      </Popover>
    </Box>
  )
}
