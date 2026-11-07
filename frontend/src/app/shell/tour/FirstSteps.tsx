import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked'
import { useNavigate } from 'react-router'
import { TextAction } from '@/shared/ui/DetailSection'
import { radius } from '@/shared/theme/tokens'
import { focusChatInput } from './tourSteps'
import { useTourStore, type ChecklistItem } from './tourStore'

interface Task {
  id: ChecklistItem
  title: string
  text: string
  action: string
  run: (navigate: ReturnType<typeof useNavigate>) => void
}

const TASKS: Task[] = [
  {
    id: 'ask',
    title: 'Eine Frage stellen',
    text: 'In eigenen Worten, wie an eine Kollegin.',
    action: 'Zum Eingabefeld',
    run: () => focusChatInput(),
  },
  {
    id: 'evidence',
    title: 'Belege prüfen',
    text: 'Ein Klick auf eine Ziffer zeigt die Stelle im Dokument.',
    action: 'Beispiel öffnen',
    run: (navigate) => navigate('/spaces/space-personal/chats/chat-personal-1'),
  },
  {
    id: 'spaces',
    title: 'Spaces kennenlernen',
    text: 'Privat arbeiten oder Chats und Wissen im Team teilen.',
    action: 'Spaces ansehen',
    run: (navigate) => navigate('/spaces'),
  },
  {
    id: 'catalog',
    title: 'Wissen finden',
    text: 'Alle Bibliotheken und Prompts, die Sie lesen dürfen.',
    action: 'Katalog öffnen',
    run: (navigate) => navigate('/catalog'),
  },
]

/**
 * Variant C: "Erste Schritte" in place of the empty chat's greeting - four tasks that tick when
 * their action is used. Nothing is modal; "Ausblenden" folds it back to the plain greeting, and the
 * profile menu brings it back.
 */
export default function FirstSteps() {
  const navigate = useNavigate()
  const { checked, check, hideChecklist } = useTourStore()
  const doneCount = TASKS.filter((task) => checked.includes(task.id)).length

  return (
    <Box
      component="section"
      aria-labelledby="first-steps-title"
      sx={{ width: '100%', maxWidth: 520, px: 2, color: 'text.primary' }}
    >
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5, mb: 1.5 }}>
        <Typography id="first-steps-title" component="h2" sx={{ fontSize: 20, fontWeight: 600 }}>
          Erste Schritte
        </Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          {doneCount} von {TASKS.length} erledigt
        </Typography>
      </Box>
      <Box
        component="ul"
        sx={{
          listStyle: 'none',
          m: 0,
          p: 0,
          border: 1,
          borderColor: 'divider',
          borderRadius: `${radius.md}px`,
          bgcolor: 'background.paper',
        }}
      >
        {TASKS.map((task, index) => {
          const done = checked.includes(task.id)
          const Icon = done ? CheckCircleIcon : RadioButtonUncheckedIcon
          return (
            <Box
              component="li"
              key={task.id}
              sx={{
                display: 'grid',
                gridTemplateColumns: 'auto 1fr',
                columnGap: 1.5,
                rowGap: 0.5,
                alignItems: 'start',
                px: 2,
                py: 1.5,
                borderTop: index === 0 ? 0 : 1,
                borderColor: 'divider',
              }}
            >
              <Icon
                aria-hidden
                sx={{ mt: '2px', fontSize: 20, color: done ? 'primary.main' : 'text.secondary' }}
              />
              <Box
                sx={{
                  minWidth: 0,
                  display: 'flex',
                  flexWrap: 'wrap',
                  alignItems: 'baseline',
                  columnGap: 2,
                }}
              >
                <Box sx={{ flex: '1 1 220px', minWidth: 0 }}>
                  <Typography sx={{ fontSize: 14.5, fontWeight: 500 }}>
                    {task.title}
                    <Box component="span" sx={visuallyHidden}>
                      {done ? ', erledigt' : ', offen'}
                    </Box>
                  </Typography>
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    {task.text}
                  </Typography>
                </Box>
                <TextAction
                  context={`: ${task.title}`}
                  onClick={() => {
                    check(task.id)
                    task.run(navigate)
                  }}
                >
                  {task.action}
                </TextAction>
              </Box>
            </Box>
          )
        })}
      </Box>
      <Box sx={{ mt: 1, display: 'flex', justifyContent: 'flex-end' }}>
        <TextAction onClick={hideChecklist} context=" Erste Schritte">
          Ausblenden
        </TextAction>
      </Box>
    </Box>
  )
}
