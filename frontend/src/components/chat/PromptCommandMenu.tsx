import Box from '@mui/material/Box'
import ClickAwayListener from '@mui/material/ClickAwayListener'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListSubheader from '@mui/material/ListSubheader'
import Paper from '@mui/material/Paper'
import Popper from '@mui/material/Popper'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import TextSnippetOutlinedIcon from '@mui/icons-material/TextSnippetOutlined'
import { fontFamily } from '../../theme/tokens'
import type { AvailablePrompt } from '../../types/api'
import { promptOptionId } from './promptTemplate'

interface PromptCommandMenuProps {
  open: boolean
  anchorEl: HTMLElement | null
  listboxId: string
  /** The matching prompts, in the server's order: space-associated libraries first. */
  prompts: AvailablePrompt[]
  highlightedIndex: number
  isLoading: boolean
  error: string | null
  onHighlight: (index: number) => void
  onSelect: (prompt: AvailablePrompt) => void
  onClose: () => void
}

/**
 * The '/' selection above the chat input (#1903), built like the '@' popper: a listbox whose
 * options are grouped by prompt library, the input keeping focus and driving the highlight.
 */
export default function PromptCommandMenu({
  open,
  anchorEl,
  listboxId,
  prompts,
  highlightedIndex,
  isLoading,
  error,
  onHighlight,
  onSelect,
  onClose,
}: PromptCommandMenuProps) {
  const groups: { libraryId: string; libraryName: string; associated: boolean; start: number }[] =
    []
  prompts.forEach((prompt, index) => {
    if (groups.at(-1)?.libraryId !== prompt.libraryId) {
      groups.push({
        libraryId: prompt.libraryId,
        libraryName: prompt.libraryName,
        associated: prompt.associatedWithSpace,
        start: index,
      })
    }
  })

  const message = error
    ? error
    : isLoading && prompts.length === 0
      ? 'Prompts werden geladen …'
      : prompts.length === 0
        ? 'Kein passender Prompt gefunden'
        : null

  return (
    <Popper
      open={open}
      anchorEl={anchorEl}
      placement="top-start"
      style={{ zIndex: 1300, width: anchorEl?.offsetWidth }}
      modifiers={[{ name: 'offset', options: { offset: [0, 8] } }]}
    >
      <ClickAwayListener onClickAway={onClose}>
        <Paper elevation={4} sx={{ maxHeight: 320, overflowY: 'auto' }}>
          <Typography
            component="div"
            sx={{
              px: 1.5,
              py: 1,
              fontFamily: fontFamily.mono,
              fontSize: 9.5,
              letterSpacing: '0.08em',
              textTransform: 'uppercase',
              color: 'text.secondary',
              borderBottom: 1,
              borderColor: 'divider',
            }}
          >
            Prompt einsetzen
          </Typography>
          {message !== null ? (
            <Typography variant="body2" sx={{ color: 'text.secondary', p: 1.5 }}>
              {message}
            </Typography>
          ) : (
            <List id={listboxId} role="listbox" aria-label="Prompts" dense disablePadding>
              {groups.map((group, groupIndex) => {
                const end = groups[groupIndex + 1]?.start ?? prompts.length
                const headerId = `${listboxId}-group-${groupIndex}`
                return (
                  <Box
                    key={group.libraryId}
                    role="group"
                    aria-labelledby={headerId}
                    component="li"
                    sx={{ listStyle: 'none' }}
                  >
                    <ListSubheader
                      id={headerId}
                      component="div"
                      disableSticky
                      sx={{ lineHeight: 2.2, fontSize: 11.5 }}
                    >
                      {group.libraryName}
                      {group.associated && ' · diesem Space zugeordnet'}
                    </ListSubheader>
                    <Box component="ul" role="presentation" sx={{ p: 0, m: 0 }}>
                      {prompts.slice(group.start, end).map((prompt, offset) => {
                        const index = group.start + offset
                        return (
                          <ListItemButton
                            key={prompt.id}
                            component="li"
                            id={promptOptionId(listboxId, index)}
                            role="option"
                            aria-selected={index === highlightedIndex}
                            selected={index === highlightedIndex}
                            onMouseDown={(event) => event.preventDefault()}
                            onMouseEnter={() => onHighlight(index)}
                            onClick={() => onSelect(prompt)}
                            sx={{
                              gap: 1.25,
                              py: 0.75,
                              alignItems: 'flex-start',
                              '&.Mui-selected': {
                                bgcolor: (theme) => alpha(theme.palette.primary.main, 0.1),
                              },
                            }}
                          >
                            <TextSnippetOutlinedIcon
                              sx={{ fontSize: 15, mt: 0.25, color: 'text.primary' }}
                            />
                            <Box sx={{ flex: 1, minWidth: 0 }}>
                              <Typography
                                component="span"
                                sx={{ display: 'block', fontSize: 13.5, color: 'text.primary' }}
                                noWrap
                              >
                                <Box
                                  component="span"
                                  sx={{ fontFamily: fontFamily.mono, fontWeight: 600 }}
                                >
                                  /{prompt.name}
                                </Box>{' '}
                                · {prompt.title}
                              </Typography>
                              {prompt.description && (
                                <Typography
                                  component="span"
                                  noWrap
                                  sx={{ display: 'block', fontSize: 12, color: 'text.secondary' }}
                                >
                                  {prompt.description}
                                </Typography>
                              )}
                            </Box>
                            {prompt.hasVariables && (
                              <Typography
                                component="span"
                                sx={{
                                  flex: 'none',
                                  fontSize: 10.5,
                                  color: 'text.secondary',
                                  border: 1,
                                  borderColor: 'divider',
                                  borderRadius: '4px',
                                  px: 1,
                                  py: 0.25,
                                }}
                              >
                                mit Formular
                              </Typography>
                            )}
                          </ListItemButton>
                        )
                      })}
                    </Box>
                  </Box>
                )
              })}
            </List>
          )}
        </Paper>
      </ClickAwayListener>
    </Popper>
  )
}
