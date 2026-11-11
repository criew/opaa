import { expect, test } from '../fixtures/auth'
import { askQuestion, startFreshChat } from '../fixtures/chat'
import { apiAs } from '../fixtures/externalAccess'
import { defaultSpaceIdOf } from '../fixtures/spaces'
import {
  accountId,
  answers,
  deleteAgentViaApi,
  RESPONSIBLE_UNIT,
  restoreAgentSettings,
  setAgentSettings,
  setTemplatesEnabled,
  type AgentSettings,
} from '../fixtures/agents'
import type { Page } from '@playwright/test'

/**
 * Epic #1747, die Szenarien 1 und 2 (test(e2e) #3680): Ist der Schalter „Agenten“ aus, gibt es
 * weder Agentenwahl noch Katalogeintrag noch Vorlagen, und der gewöhnliche Chat antwortet wie
 * bisher. Ist er an, legt nur an, wer das Anlegerecht „Agenten anlegen“ hat; ohne es nennt die
 * Anlage den Grund vorab, nach der Freigabe der Systemverwaltung entsteht der Agent über den
 * Assistenten.
 */

const SUFFIX = Date.now().toString(36)
const AGENT_NAME = `E2E-Anlage-${SUFFIX}`
const AGENT_PURPOSE = 'Prüft Fristen in Dienstanweisungen.'

async function openNewCatalogEntry(page: Page): Promise<void> {
  await page.goto('/catalog')
  await page.getByRole('button', { name: 'Neuer Katalogeintrag', exact: true }).first().click()
  await expect(page.getByRole('radio', { name: /^Prompts/ })).toBeVisible()
}

test.describe.serial('Agenten: Schalter und Anlegerecht (#3680)', () => {
  let agentsBefore: AgentSettings | null = null
  let templatesBefore: boolean | null = null
  let capabilityGrantId: string | null = null
  let createdAgentId: string | null = null

  test.beforeAll(async () => {
    agentsBefore = await setAgentSettings(false)
    templatesBefore = await setTemplatesEnabled(false)
  })

  test.afterAll(async () => {
    const admin = await apiAs('dev-admin')
    try {
      if (capabilityGrantId) {
        await admin.delete(`/api/v1/admin/capabilities/CREATE_AGENT/grants/${capabilityGrantId}`)
      }
    } finally {
      await admin.dispose()
    }
    if (createdAgentId) {
      await deleteAgentViaApi('dev-user', createdAgentId)
    }
    await restoreAgentSettings(agentsBefore)
    if (templatesBefore !== null) {
      await setTemplatesEnabled(templatesBefore)
    }
  })

  test('1. Schalter aus: keine Agentenwahl, kein Katalogeintrag, keine Vorlagen', async ({
    authenticatedPage: page,
  }) => {
    await startFreshChat(page)
    const input = page.getByRole('combobox', { name: 'Nachricht eingeben' })
    await input.pressSequentially('@')
    // The menu is open (it offers the knowledge chips) but names no agents.
    await expect(page.getByRole('option').first()).toBeVisible()
    await expect(page.getByRole('group', { name: 'Agenten' })).toHaveCount(0)
    await expect(page.getByRole('option', { name: /^Agent / })).toHaveCount(0)
    await input.clear()

    await openNewCatalogEntry(page)
    await expect(page.getByRole('radio', { name: /^Agenten/ })).toHaveCount(0)
    await expect(page.getByRole('radio', { name: /^Vorlagen/ })).toHaveCount(0)

    const api = await apiAs('dev-admin')
    try {
      expect((await api.get('/api/v1/agents')).status()).toBe(404)
      const available = await api.get('/api/v1/agents/available', {
        params: { spaceId: await defaultSpaceIdOf('dev-admin') },
      })
      expect(available.status(), await available.text()).toBe(200)
      expect(await available.json()).toEqual([])
      expect(await (await api.get('/api/v1/agents/availability')).json()).toEqual({
        enabled: false,
      })
      expect(await (await api.get('/api/v1/document-templates/availability')).json()).toEqual({
        enabled: false,
      })
    } finally {
      await api.dispose()
    }

    // The ordinary chat answers as before: no agent label, no steps, no draft mark.
    await startFreshChat(page)
    await askQuestion(page, `Wie lange dauert ein Antrag? (${SUFFIX})`)
    await expect(answers(page)).toHaveCount(1)
    await expect(page.getByTestId('question-agent')).toHaveCount(0)
    await expect(page.getByTestId('agent-stamp')).toHaveCount(0)
    await expect(page.getByRole('button', { name: /Schritte?$/ })).toHaveCount(0)
  })

  test('2. Anlegerecht: ohne es gesperrt mit Grund, nach der Freigabe legt die Person an', async ({
    regularUserPage: page,
  }) => {
    await setAgentSettings(true)

    // Without the Anlegerecht the type is not offered, and the wizard names the reason up front.
    await openNewCatalogEntry(page)
    await expect(page.getByRole('radio', { name: /^Agenten/ })).toHaveCount(0)
    await page.goto('/agents/new')
    const reason = page.getByText(/Ihnen fehlt das Anlegerecht „Agenten anlegen“/)
    await expect(reason).toBeVisible()
    await page.getByLabel(/^Name/).fill(AGENT_NAME)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await page.getByLabel('Verantwortliche Stelle').fill(RESPONSIBLE_UNIT)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await page.getByRole('button', { name: 'Anlegen', exact: true }).click()
    await expect(reason).toBeVisible()
    await expect(page).toHaveURL(/\/agents\/new$/)

    // The system administration grants the right; no new sign-in is needed.
    const admin = await apiAs('dev-admin')
    try {
      const granted = await admin.post('/api/v1/admin/capabilities/CREATE_AGENT/grants', {
        data: { subjectType: 'USER', subjectId: await accountId('dev-user') },
      })
      expect(granted.status(), await granted.text()).toBe(201)
      capabilityGrantId = ((await granted.json()) as { id: string }).id
    } finally {
      await admin.dispose()
    }

    await openNewCatalogEntry(page)
    await page.getByRole('radio', { name: /^Agenten/ }).click()
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await expect(page.getByRole('heading', { level: 2, name: 'Stammdaten' })).toBeVisible()
    await expect(page.getByText(/Ihnen fehlt das Anlegerecht/)).toHaveCount(0)
    await page.getByLabel(/^Name/).fill(AGENT_NAME)
    await page.getByLabel(/^Zweck/).fill(AGENT_PURPOSE)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await expect(page.getByRole('heading', { level: 2, name: 'Freigaben' })).toBeVisible()
    // Without a responsible unit the agent cannot be saved: the step does not let go.
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await expect(
      page.getByText(
        'Geben Sie die verantwortliche Stelle an: eine Gruppe, Organisationseinheit oder Funktion.',
      ),
    ).toBeVisible()
    await expect(page.getByRole('heading', { level: 2, name: 'Freigaben' })).toBeVisible()
    await page.getByLabel('Verantwortliche Stelle').fill(RESPONSIBLE_UNIT)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await expect(page.getByRole('heading', { level: 2, name: 'Prüfen' })).toBeVisible()
    await expect(page.getByText(AGENT_PURPOSE)).toBeVisible()
    await expect(page.getByText(RESPONSIBLE_UNIT)).toBeVisible()
    await page.getByRole('button', { name: 'Anlegen', exact: true }).click()

    await expect(page.getByRole('heading', { level: 1, name: AGENT_NAME })).toBeVisible()
    createdAgentId = new URL(page.url()).pathname.split('/').pop() ?? null
    expect(createdAgentId).toMatch(/^[0-9a-f-]{36}$/)
    await expect(page.getByText('Fassung 1')).toBeVisible()
    await expect(
      page.getByText('Noch kein Auftrag. Der Agent arbeitet nur nach den festen Regeln von OPAA.'),
    ).toBeVisible()
  })
})
