import Box from '@mui/material/Box'
import ClickAwayListener from '@mui/material/ClickAwayListener'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListSubheader from '@mui/material/ListSubheader'
import Paper from '@mui/material/Paper'
import Popper from '@mui/material/Popper'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import { fontFamily, fontSize, iconSize } from '@/shared/theme/tokens'
import MetaBadge from '@/shared/ui/MetaBadge'
import { actionOptionId } from './chatActions'
import type { ChatActionTexts } from './chatActions'
import type { SourcedChatAction } from './useChatActions'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

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
  onRetry: () => void
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
  onRetry,
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

  const loading = !error && isLoading && actions.length === 0
  const message = !hasActions ? texts.none : actions.length === 0 ? texts.noMatch : null

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
              fontSize: fontSize.label,
              letterSpacing: '0.08em',
              textTransform: 'uppercase',
              color: 'text.secondary',
              borderBottom: 1,
              borderColor: 'divider',
            }}
          >
            {texts.heading}
          </Typography>
          {loading ? (
            <Box sx={{ p: 1.5 }}>
              <LoadingState shape="lines" count={3} />
            </Box>
          ) : error ? (
            // The input keeps focus while the retry is pressed, as for every option.
            <Box sx={{ p: 1 }} onMouseDown={(event) => event.preventDefault()}>
              <LoadError message={texts.loadFailed} detail={error} onRetry={onRetry} />
            </Box>
          ) : message !== null ? (
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
                      sx={{ lineHeight: 2.2, fontSize: fontSize.meta }}
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
                            <Icon sx={{ fontSize: iconSize.sm, mt: 0.25, color: 'text.primary' }} />
                            <Box sx={{ flex: 1, minWidth: 0 }}>
                              <Typography
                                component="span"
                                sx={{
                                  display: 'block',
                                  fontSize: fontSize.text,
                                  color: 'text.primary',
                                }}
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
                                  sx={{
                                    display: 'block',
                                    fontSize: fontSize.meta,
                                    color: 'text.secondary',
                                  }}
                                >
                                  {action.description}
                                </Typography>
                              )}
                            </Box>
                            {action.badge && (
                              <Box component="span" sx={{ flex: 'none', display: 'inline-flex' }}>
                                <MetaBadge>{action.badge}</MetaBadge>
                              </Box>
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
