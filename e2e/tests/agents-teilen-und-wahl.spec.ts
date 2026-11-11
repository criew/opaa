import { expect, test } from '../fixtures/auth'
import { apiAs } from '../fixtures/externalAccess'
import { createPromptLibraryViaApi, createReleasedGroupViaApi } from '../fixtures/promptLibraries'
import { createSpaceWithMember, deleteSpaceViaApi } from '../fixtures/privateLibraries'
import {
  addSpaceMember,
  answers,
  assignToSpace,
  chooseAgent,
  createAgentViaApi,
  createIndexedLibrary,
  createPromptViaApi,
  deleteAgentViaApi,
  deleteGroupViaApi,
  grantViewer,
  accountId,
  restoreAgentSettings,
  setAgentSettings,
  setToolCapability,
  type AgentSettings,
} from '../fixtures/agents'
import type { Page } from '@playwright/test'

/**
 * Die Szenarien 3 und 4. Teilen: Der Agent wird an eine Gruppe
 * freigegeben; ein Gruppenmitglied wählt ihn per `@`, eine fremde Person im selben Space findet ihn
 * nicht. Wahl: Agent und Suchbereich sind im Menü und im Eingabefeld unterscheidbar, und `@Agent`
 * mit `/Prompt` setzt den Prompttext ein und läuft über den Agenten.
 *
 * dev-admin besitzt Agent, Bibliotheken und Space; dev-user ist über die Gruppe Mitglied des
 * Teilens, dev-outsider ist Mitglied des Space und liest die Bibliothek, aber nicht den Agenten.
 */

const ADMIN = 'dev-admin'
const MEMBER = 'dev-user'
const OUTSIDER = 'dev-outsider'
const SUFFIX = Date.now().toString(36)
const AGENT_NAME = `E2E-Fristen-${SUFFIX}`
const AGENT_PURPOSE = 'Beantwortet Fragen zu Bearbeitungsfristen.'
const LIBRARY_NAME = `E2E-Fristen-Wissen-${SUFFIX}`
const GROUP_NAME = `E2E-Agentengruppe-${SUFFIX}`
const PROMPT_COMMAND = `fristen-${SUFFIX}`
const PROMPT_TITLE = `Fristenübersicht ${SUFFIX}`
const PROMPT_TEXT = 'Nenne alle Bearbeitungsfristen aus den Dienstanweisungen.'

async function openSpaceChat(page: Page, spaceId: string): Promise<void> {
  await page.goto(`/spaces/${spaceId}/chats/new`)
  await expect(page.getByRole('combobox', { name: 'Nachricht eingeben' })).toBeVisible()
}

/** Opens the `@` menu once its first entries are there. */
async function openMentionMenu(page: Page): Promise<void> {
  const input = page.getByRole('combobox', { name: 'Nachricht eingeben' })
  await input.pressSequentially('@')
  await expect(page.getByRole('option').first()).toBeVisible()
}

test.describe.serial('Agenten: Teilen, @-Wahl und Suchbereich', () => {
  let agentsBefore: AgentSettings | null = null
  let capabilityBefore: Awaited<ReturnType<typeof setToolCapability>> | null = null
  let spaceId = ''
  let agentId = ''
  let groupId = ''
  let libraryId = ''
  let promptLibraryId = ''

  test.beforeAll(async () => {
    agentsBefore = await setAgentSettings(true)
    capabilityBefore = await setToolCapability('CAPABLE')

    libraryId = await createIndexedLibrary(ADMIN, LIBRARY_NAME, [
      {
        name: `fristen-${SUFFIX}.txt`,
        text: 'Dienstanweisung Fristen: Anträge auf Wohngeld werden binnen zehn Tagen bearbeitet.',
      },
    ])
    // A first request provisions the account, which the group lookup needs on a fresh stack.
    await accountId(MEMBER)
    groupId = await createReleasedGroupViaApi(GROUP_NAME, 'Dev User')
    // The library is readable by both members of the space: the agent is the only difference.
    await grantViewer(ADMIN, 'KNOWLEDGE_LIBRARY', libraryId, {
      subjectType: 'GROUP',
      subjectId: groupId,
    })
    await grantViewer(ADMIN, 'KNOWLEDGE_LIBRARY', libraryId, {
      subjectType: 'USER',
      subjectId: await accountId(OUTSIDER),
    })

    promptLibraryId = await createPromptLibraryViaApi(ADMIN, { name: `E2E-Prompts-${SUFFIX}` })
    await createPromptViaApi(ADMIN, promptLibraryId, {
      name: PROMPT_COMMAND,
      title: PROMPT_TITLE,
      text: PROMPT_TEXT,
    })
    await grantViewer(ADMIN, 'PROMPT_LIBRARY', promptLibraryId, {
      subjectType: 'USER',
      subjectId: await accountId(MEMBER),
    })

    spaceId = await createSpaceWithMember(`E2E-Agentenraum-${SUFFIX}`, ADMIN, MEMBER)
    await addSpaceMember(ADMIN, spaceId, OUTSIDER)
    agentId = await createAgentViaApi(ADMIN, {
      name: AGENT_NAME,
      description: AGENT_PURPOSE,
      libraryIds: [libraryId],
    })
    await assignToSpace(ADMIN, spaceId, 'KNOWLEDGE_LIBRARY', libraryId)
    await assignToSpace(ADMIN, spaceId, 'PROMPT_LIBRARY', promptLibraryId)
    await assignToSpace(ADMIN, spaceId, 'AGENT', agentId)
  })

  test.afterAll(async () => {
    if (spaceId) await deleteSpaceViaApi(ADMIN, spaceId)
    if (agentId) await deleteAgentViaApi(ADMIN, agentId)
    const api = await apiAs(ADMIN)
    try {
      for (const [type, id] of [
        ['libraries', libraryId],
        ['prompt-libraries', promptLibraryId],
      ]) {
        if (!id) continue
        if (type === 'libraries') {
          const documents = (await (
            await api.get(`/api/v1/libraries/${id}/documents?size=100`)
          ).json()) as { items: Array<{ id: string }> }
          for (const document of documents.items) {
            await api.delete(`/api/v1/libraries/${id}/documents/${document.id}`)
          }
        }
        await api.delete(`/api/v1/${type}/${id}`)
      }
    } finally {
      await api.dispose()
    }
    if (groupId) await deleteGroupViaApi(groupId)
    await restoreAgentSettings(agentsBefore)
    if (capabilityBefore && capabilityBefore !== 'CAPABLE') {
      await setToolCapability(capabilityBefore)
    }
  })

  test('3. Teilen: ein Gruppenmitglied wählt den Agenten, eine fremde Person findet ihn nicht', async ({
    authenticatedPage: owner,
    regularUserPage: member,
    outsiderPage: outsider,
  }) => {
    // Assigned to the space is not shared: nobody but the owner reads the agent yet.
    await openSpaceChat(member, spaceId)
    await openMentionMenu(member)
    await expect(member.getByRole('group', { name: 'Agenten' })).toHaveCount(0)

    await owner.goto(`/agents/${agentId}/freigaben`)
    await owner.getByRole('button', { name: 'Freigeben' }).click()
    const recipient = owner.getByRole('combobox', { name: 'Person oder Gruppe suchen' })
    await recipient.click()
    await recipient.fill(GROUP_NAME)
    await owner.getByRole('option', { name: GROUP_NAME }).click()
    await owner.getByRole('button', { name: 'Freigeben' }).last().click()
    await expect(
      owner
        .getByRole('region', { name: 'Berechtigungen' })
        .getByRole('button', { name: `Weitere Aktionen für „${GROUP_NAME}“` }),
    ).toBeVisible()

    await openSpaceChat(member, spaceId)
    await openMentionMenu(member)
    await expect(
      member.getByRole('group', { name: 'Agenten' }).getByRole('option', {
        name: new RegExp(`^${AGENT_NAME}\\s*Agent · ersetzt den Suchbereich$`),
      }),
    ).toBeVisible()

    await openSpaceChat(outsider, spaceId)
    await openMentionMenu(outsider)
    // The knowledge of the space is there for the outsider, the agent is not.
    await expect(outsider.getByRole('option', { name: new RegExp(LIBRARY_NAME) })).toBeVisible()
    await expect(outsider.getByRole('group', { name: 'Agenten' })).toHaveCount(0)
    await expect(
      outsider.getByRole('option', { name: /Agent · ersetzt den Suchbereich$/ }),
    ).toHaveCount(0)
  })

  test('4. @-Wahl: Agent und Suchbereich sind unterscheidbar, @Agent mit /Prompt läuft über den Agenten', async ({
    regularUserPage: page,
  }) => {
    await openSpaceChat(page, spaceId)
    await openMentionMenu(page)

    // Two groups with their own headings; the entries differ by wording, not only by position.
    const knowledge = page.getByRole('group', { name: 'Wissen' })
    const agents = page.getByRole('group', { name: 'Agenten' })
    await expect(knowledge.getByRole('option', { name: /@Space-Wissen/ })).toContainText(
      'Wissen des Space',
    )
    const library = knowledge.getByRole('option', { name: new RegExp(LIBRARY_NAME) })
    await expect(library).toContainText('Bibliothek · verengt die Suche')
    const agent = agents.getByRole('option', { name: /Agent · ersetzt den Suchbereich$/ })
    await expect(agent).toHaveCount(1)
    await expect(agent).toContainText('Agent · ersetzt den Suchbereich')

    // A library narrows the search and stays a chip of its own kind beside the agent's chip.
    await library.click()
    await expect(
      page.getByRole('button', { name: `Bibliotheksreferenz ${LIBRARY_NAME} entfernen` }),
    ).toBeVisible()
    await chooseAgent(page, AGENT_NAME)
    const agentChip = page.getByTestId('chosen-agent-chip')
    await expect(agentChip).toHaveAccessibleName(`Agent ${AGENT_NAME} entfernen`)
    await expect(
      page.getByRole('button', { name: `Bibliotheksreferenz ${LIBRARY_NAME} entfernen` }),
    ).toBeVisible()
    const input = page.getByRole('combobox', { name: 'Nachricht eingeben' })
    await expect(input).toHaveValue('')

    // /Prompt puts the prompt's text in; the agent works on it.
    await input.fill(`/${PROMPT_COMMAND}`)
    await page.getByRole('option', { name: new RegExp(`/${PROMPT_COMMAND}`) }).click()
    await expect(input).toHaveValue(PROMPT_TEXT)
    await expect(agentChip).toBeVisible()
    await page.getByRole('button', { name: 'Senden' }).click()

    await expect(page.getByTestId('question-agent')).toHaveText(`Agent: ${AGENT_NAME}`)
    await expect(page.getByTestId('used-prompt')).toContainText(`Prompt: ${PROMPT_TITLE}`)
    await expect(
      page.getByTestId('message-list').getByText(PROMPT_TEXT, { exact: true }),
    ).toBeVisible()
    const answer = answers(page).first()
    await expect(answer).toContainText('Ergebnis des Agenten.')
    await expect(answer).toContainText('1 Schritt')
    await expect(answer.getByTestId('agent-stamp')).toContainText('Entwurf')
    // The agent was for this one question; the next one starts without it.
    await expect(page.getByTestId('chosen-agent-chip')).toHaveCount(0)
  })
})
