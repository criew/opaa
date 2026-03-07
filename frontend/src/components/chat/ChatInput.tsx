import type { KeyboardEvent } from 'react'
import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import FormControl from '@mui/material/FormControl'
import IconButton from '@mui/material/IconButton'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import SendIcon from '@mui/icons-material/Send'
import { CHAT_MAX_WIDTH } from '../../theme/theme'
import type { WorkspaceListResponse } from '../../types/api'

interface ChatInputProps {
  onSend: (message: string) => void
  disabled?: boolean
  workspaces?: WorkspaceListResponse[]
  selectedWorkspaceId?: string | null
  onWorkspaceFilterChange?: (workspaceId: string | null) => void
}

export default function ChatInput({
  onSend,
  disabled = false,
  workspaces = [],
  selectedWorkspaceId = null,
  onWorkspaceFilterChange,
}: ChatInputProps) {
  const [value, setValue] = useState('')
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const wasDisabled = useRef(false)

  useEffect(() => {
    if (wasDisabled.current && !disabled) {
      inputRef.current?.focus()
    }
    wasDisabled.current = disabled
  }, [disabled])

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

  return (
    <Box sx={{ flexShrink: 0, p: 2, bgcolor: 'background.default' }}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1.5,
          maxWidth: CHAT_MAX_WIDTH,
          mx: 'auto',
          bgcolor: 'background.paper',
          border: 1,
          borderColor: 'divider',
          borderRadius: '16px',
          p: 1,
        }}
      >
        <Box sx={{ display: 'flex', flexDirection: 'column', flexGrow: 1, gap: 1 }}>
          <FormControl size="small" sx={{ width: 280 }}>
            <InputLabel id="workspace-filter-label">Search in</InputLabel>
            <Select
              labelId="workspace-filter-label"
              value={selectedWorkspaceId ?? 'all'}
              label="Search in"
              disabled={disabled}
              onChange={(event) => {
                const next = event.target.value
                onWorkspaceFilterChange?.(next === 'all' ? null : next)
              }}
            >
              <MenuItem value="all">All Workspaces</MenuItem>
              {workspaces.map((workspace) => (
                <MenuItem key={workspace.id} value={workspace.id}>
                  {workspace.name}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
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
        </Box>
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
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ display: 'block', textAlign: 'center', mt: 1, maxWidth: CHAT_MAX_WIDTH, mx: 'auto' }}
      >
        Enter to send, Shift+Enter for new line
      </Typography>
    </Box>
  )
}
