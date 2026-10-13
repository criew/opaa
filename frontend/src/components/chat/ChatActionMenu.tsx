import Box from '@mui/material/Box'
import ClickAwayListener from '@mui/material/ClickAwayListener'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListSubheader from '@mui/material/ListSubheader'
import Paper from '@mui/material/Paper'
import Popper from '@mui/material/Popper'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import { fontFamily } from '../../theme/tokens'
import { actionOptionId } from './chatActions'
import type { ChatActionTexts } from './chatActions'
import type { SourcedChatAction } from './useChatActions'

interface ChatActionMenuProps {
  open: boolean
  anchorEl: HTMLElement | null
  listboxId: string
  /** The matching entries, source by source, each in its source's order. */
  actions: SourcedChatAction[]
  /** Whether the sources offer anything at all, matching or not. */
  hasActions: boolean
  texts: ChatActionTexts
  highlightedIndex: number
  isLoading: boolean
  error: string | null
  onHighlight: (index: number) => void
  onSelect: (action: SourcedChatAction) => void
  onClose: () => void
}

/**
 * The '/' menu above the chat input, built like the '@' popper: a listbox whose options are
 * grouped by the heading their source gives them (a prompt library), the input keeping focus and
 * driving the highlight.
 */
export default function ChatActionMenu({
  open,
  anchorEl,
  listboxId,
  actions,
  hasActions,
  texts,
  highlightedIndex,
  isLoading,
  error,
  onHighlight,
  onSelect,
  onClose,
}: ChatActionMenuProps) {
  const groups: { key: string; label: string; start: number }[] = []
  actions.forEach((action, index) => {
    const key = `${action.source.id}:${action.group.id}`
    if (groups.at(-1)?.key !== key) {
      groups.push({ key, label: action.group.label, start: index })
    }
  })

  const message = error
    ? error
    : isLoading && actions.length === 0
      ? texts.loading
      : !hasActions
        ? texts.none
        : actions.length === 0
          ? texts.noMatch
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
            {texts.heading}
          </Typography>
          {message !== null ? (
            <Typography variant="body2" sx={{ color: 'text.secondary', p: 1.5 }}>
              {message}
            </Typography>
          ) : (
            <List id={listboxId} role="listbox" aria-label={texts.listLabel} dense disablePadding>
              {groups.map((group, groupIndex) => {
                const end = groups[groupIndex + 1]?.start ?? actions.length
                const headerId = `${listboxId}-group-${groupIndex}`
                return (
                  <Box
                    key={group.key}
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
                      {group.label}
                    </ListSubheader>
                    <Box component="ul" role="presentation" sx={{ p: 0, m: 0 }}>
                      {actions.slice(group.start, end).map((action, offset) => {
                        const index = group.start + offset
                        const Icon = action.source.Icon
                        return (
                          <ListItemButton
                            key={action.key}
                            component="li"
                            id={actionOptionId(listboxId, index)}
                            role="option"
                            aria-selected={index === highlightedIndex}
                            selected={index === highlightedIndex}
                            onMouseDown={(event) => event.preventDefault()}
                            onMouseEnter={() => onHighlight(index)}
                            onClick={() => onSelect(action)}
                            sx={{
                              gap: 1.25,
                              py: 0.75,
                              alignItems: 'flex-start',
                              '&.Mui-selected': {
                                bgcolor: (theme) => alpha(theme.palette.primary.main, 0.1),
                              },
                            }}
                          >
                            <Icon sx={{ fontSize: 15, mt: 0.25, color: 'text.primary' }} />
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
                                  /{action.command}
                                </Box>{' '}
                                · {action.title}
                              </Typography>
                              {action.description && (
                                <Typography
                                  component="span"
                                  noWrap
                                  sx={{ display: 'block', fontSize: 12, color: 'text.secondary' }}
                                >
                                  {action.description}
                                </Typography>
                              )}
                            </Box>
                            {action.badge && (
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
                                {action.badge}
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
