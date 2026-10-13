import TextSnippetOutlinedIcon from '@mui/icons-material/TextSnippetOutlined'
import { getPrompt, listAvailablePrompts } from '../../services/promptLibraryApi'
import { resolvePromptText } from '../../utils/promptTemplate'
import type { ChatActionOption, ChatActionSource } from './chatActions'
import PromptVariablesDialog from './PromptVariablesDialog'

/** The prompt an entry stands for: the menu names it, `choose` fetches it from its library. */
interface PromptOption extends ChatActionOption {
  libraryId: string
}

/**
 * The prompts of the prompt libraries associated with the chat's space, readable by the person
 * (#1903). Choosing one fetches it; a prompt with variables asks for them first. The question is
 * marked with the prompt it was built from.
 */
export const promptActionSource: ChatActionSource = {
  id: 'prompts',
  feature: 'promptLibraries',
  Icon: TextSnippetOutlinedIcon,
  texts: {
    heading: 'Prompt einsetzen',
    listLabel: 'Prompts',
    loading: 'Prompts werden geladen …',
    none: 'Diesem Space sind keine Prompts zugeordnet',
    noMatch: 'Kein passender Prompt gefunden',
    loadFailed: 'Prompts konnten nicht geladen werden',
    chooseFailed: 'Der Prompt konnte nicht geladen werden',
  },
  load: async ({ spaceId }) => {
    // Prompts exist in a chat only through its space.
    if (!spaceId) return []
    const prompts = await listAvailablePrompts(spaceId)
    return prompts.map((prompt): PromptOption => ({
      id: prompt.id,
      libraryId: prompt.libraryId,
      group: { id: prompt.libraryId, label: prompt.libraryName },
      command: prompt.name,
      title: prompt.title,
      description: prompt.description,
      badge: prompt.hasVariables ? 'mit Formular' : null,
    }))
  },
  choose: async (option, { userName }) => {
    const prompt = await getPrompt((option as PromptOption).libraryId, option.id)
    const mark = { id: prompt.id, title: prompt.title }
    if (prompt.variables.length === 0) {
      return { kind: 'insert', text: resolvePromptText(prompt.text, [], {}, { userName }), mark }
    }
    return {
      kind: 'form',
      mark,
      renderForm: ({ onInsert, onCancel }) => (
        <PromptVariablesDialog
          prompt={prompt}
          userName={userName}
          onCancel={onCancel}
          onInsert={onInsert}
        />
      ),
    }
  },
  markLabel: (mark) => `Prompt: ${mark.title}`,
}
