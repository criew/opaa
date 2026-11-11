import { expect, test } from '../fixtures/auth'
import { apiAs } from '../fixtures/externalAccess'
import { createSpaceWithMember, deleteSpaceViaApi } from '../fixtures/privateLibraries'
import {
  accountId,
  answers,
  assignToSpace,
  chooseAgent,
  createAgentViaApi,
  createIndexedLibrary,
  deleteAgentViaApi,
  grantViewer,
  restoreAgentSettings,
  setAgentSettings,
  setToolCapability,
  type AgentSettings,
} from '../fixtures/agents'
import type { Page } from '@playwright/test'

/**
 * Epic #1747, Szenario 10 (test(e2e) #3680, #3677): Die Systemverwaltung sperrt einen Agenten. Er
 * verschwindet aus der Auswahl, ein offener Chat mit ihm meldet die Sperre beim nächsten Senden,
 * und nach dem Entsperren steht er wieder zur Wahl. dev-user chattet, dev-admin sperrt.
 */

const ADMIN = 'dev-admin'
const MEMBER = 'dev-user'
const SUFFIX = Date.now().toString(36)
const AGENT_NAME = `E2E-Sperre-${SUFFIX}`
const LIBRARY_NAME = `E2E-Sperre-Wissen-${SUFFIX}`
const LOCK_REASON = 'Anweisung wird überarbeitet'

async function openSpaceChat(page: Page, spaceId: string): Promise<void> {
  await page.goto(`/spaces/${spaceId}/chats/new`)
  await expect(page.getByRole('combobox', { name: 'Nachricht eingeben' })).toBeVisible()
}

async function expectInMentionMenu(page: Page, present: boolean): Promise<void> {
  const input = page.getByRole('combobox', { name: 'Nachricht eingeben' })
  await input.pressSequentially('@')
  await expect(page.getByRole('option').first()).toBeVisible()
  const agent = page.getByRole('option', { name: /Agent · ersetzt den Suchbereich$/ })
  if (present) {
    await expect(agent).toHaveCount(1)
  } else {
    await expect(agent).toHaveCount(0)
  }
  await page.keyboard.press('Escape')
  await expect(page.getByRole('listbox')).toHaveCount(0)
  await input.clear()
}

test.describe.serial('Agenten: Sperre durch die Systemverwaltung (#3680)', () => {
  let agentsBefore: AgentSettings | null = null
  let capabilityBefore: Awaited<ReturnType<typeof setToolCapability>> | null = null
  let spaceId = ''
  let agentId = ''
  let libraryId = ''

  test.beforeAll(async () => {
    agentsBefore = await setAgentSettings(true)
    capabilityBefore = await setToolCapability('CAPABLE')
    const memberId = await accountId(MEMBER)
    libraryId = await createIndexedLibrary(ADMIN, LIBRARY_NAME, [
      { name: `sperre-${SUFFIX}.txt`, text: 'Dienstanweisung: Anträge werden binnen zehn Tagen bearbeitet.' },
    ])
    await grantViewer(ADMIN, 'KNOWLEDGE_LIBRARY', libraryId, {
      subjectType: 'USER',
      subjectId: memberId,
    })
    spaceId = await createSpaceWithMember(`E2E-Sperrraum-${SUFFIX}`, ADMIN, MEMBER)
    agentId = await createAgentViaApi(ADMIN, { name: AGENT_NAME, libraryIds: [libraryId] })
    await grantViewer(ADMIN, 'AGENT', agentId, { subjectType: 'USER', subjectId: memberId })
    await assignToSpace(ADMIN, spaceId, 'KNOWLEDGE_LIBRARY', libraryId)
    await assignToSpace(ADMIN, spaceId, 'AGENT', agentId)
  })

  test.afterAll(async () => {
    if (spaceId) await deleteSpaceViaApi(ADMIN, spaceId)
    if (agentId) await deleteAgentViaApi(ADMIN, agentId)
    const api = await apiAs(ADMIN)
    try {
      if (libraryId) {
        const documents = (await (
          await api.get(`/api/v1/libraries/${libraryId}/documents?size=100`)
        ).json()) as { items: Array<{ id: string }> }
        for (const document of documents.items) {
          await api.delete(`/api/v1/libraries/${libraryId}/documents/${document.id}`)
        }
        await api.delete(`/api/v1/libraries/${libraryId}`)
      }
    } finally {
      await api.dispose()
    }
    await restoreAgentSettings(agentsBefore)
    if (capabilityBefore && capabilityBefore !== 'CAPABLE') {
      await setToolCapability(capabilityBefore)
    }
  })

  test('10. Sperre: weg aus der Auswahl, ein offener Chat meldet sie beim Senden, Entsperren holt ihn zurück', async ({
    authenticatedPage: admin,
    regularUserPage: member,
  }) => {
    // The chat is open with the agent chosen before the lock.
    await openSpaceChat(member, spaceId)
    await expectInMentionMenu(member, true)
    await chooseAgent(member, AGENT_NAME)

    await admin.goto(`/agents/${agentId}/freigaben`)
    await expect(admin.getByText('Nicht gesperrt')).toBeVisible()
    await admin.getByRole('button', { name: /^Sperren/ }).click()
    const dialog = admin.getByRole('dialog', { name: 'Sperren' })
    await dialog.getByLabel('Grund').fill(LOCK_REASON)
    await dialog.getByRole('button', { name: 'Sperren', exact: true }).click()
    await expect(dialog).toHaveCount(0)
    const panel = admin.getByRole('tabpanel', { name: 'Freigaben' })
    await expect(panel.getByText('Gesperrt', { exact: true })).toBeVisible()
    await expect(panel.getByText(new RegExp(LOCK_REASON))).toBeVisible()

    // The next send of the open chat is refused with the notice; nothing is answered.
    const question = `Wie lange dauert ein Antrag? (${SUFFIX})`
    await member.getByRole('combobox', { name: 'Nachricht eingeben' }).fill(question)
    await member.getByRole('button', { name: 'Senden' }).click()
    await expect(
      member.getByText(`Der Agent „${AGENT_NAME}“ ist gesperrt und kann gerade nicht genutzt werden.`),
    ).toBeVisible()
    await expect(answers(member)).toHaveCount(0)

    // A fresh chat does not offer it any more; a reader sees only that it is locked.
    await openSpaceChat(member, spaceId)
    await expectInMentionMenu(member, false)

    await admin.goto(`/agents/${agentId}/freigaben`)
    await admin.getByRole('button', { name: /^Entsperren/ }).click()
    await admin.getByRole('dialog').getByRole('button', { name: 'Entsperren', exact: true }).click()
    await expect(admin.getByText('Nicht gesperrt')).toBeVisible()

    await openSpaceChat(member, spaceId)
    await expectInMentionMenu(member, true)
  })
})
