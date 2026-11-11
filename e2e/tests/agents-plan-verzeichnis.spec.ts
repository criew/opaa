import { expect, test } from "../fixtures/auth";
import { apiAs } from "../fixtures/externalAccess";
import {
  expectAnswerSettled,
  expectCitedSource,
  startFreshChat,
} from "../fixtures/chat";
import { assignToDefaultSpace } from "../fixtures/spaces";
import {
  accountId,
  answers,
  askAgent,
  createAgentViaApi,
  createIndexedLibrary,
  deleteAgentViaApi,
  restoreAgentSettings,
  setAgentSettings,
  setToolCapability,
  type AgentSettings,
} from "../fixtures/agents";

/**
 * Szenario 6a: ein Agent mit Planschritt endet mit einem Plan, die Bestätigung startet einen
 * neuen Lauf, der den Plan abarbeitet; „Ändern“ führt in die Eingabe mit demselben Agenten.
 * Szenario 11: eine Person mit dem Recht „Agentenverzeichnis einsehen“ sieht alle Agenten ohne
 * Nutzungsangaben. ai-stub spielt das Modell (Planregeln im Systemtext, siehe `plainAgentStep`).
 */

const ADMIN = "dev-admin";
const MEMBER = "dev-user";
const SUFFIX = Date.now().toString(36);
const FILE = `plan-fristen-${SUFFIX}.txt`;

test.describe.serial("Agenten: Planbestätigung und Agentenverzeichnis", () => {
  test.describe.configure({ timeout: 90_000 });

  let agentsBefore: AgentSettings | null = null;
  let capabilityBefore: Awaited<ReturnType<typeof setToolCapability>> | null =
    null;
  let libraryId = "";
  let agentId = "";
  let directoryGrantId = "";
  const agentName = `E2E-Recherche-${SUFFIX}`;

  test.beforeAll(async () => {
    agentsBefore = await setAgentSettings(true);
    capabilityBefore = await setToolCapability("CAPABLE");
    libraryId = await createIndexedLibrary(ADMIN, `E2E-Plan-Wissen-${SUFFIX}`, [
      {
        name: FILE,
        text: "Dienstanweisung Fristen: Anträge auf Wohngeld werden binnen zehn Tagen bearbeitet.",
      },
    ]);
    await assignToDefaultSpace(ADMIN, "KNOWLEDGE_LIBRARY", libraryId);
    agentId = await createAgentViaApi(ADMIN, {
      name: agentName,
      description: "Recherche mit Planschritt",
      toolNames: [
        "search_knowledge",
        "read_document",
        "list_sources",
        "ask_user",
      ],
      planStep: "CONFIRM",
      libraryIds: [libraryId],
    });
    await assignToDefaultSpace(ADMIN, "AGENT", agentId);
  });

  test.afterAll(async () => {
    const api = await apiAs(ADMIN);
    try {
      if (directoryGrantId) {
        await api.delete(
          `/api/v1/admin/capabilities/VIEW_AGENT_DIRECTORY/grants/${directoryGrantId}`,
        );
      }
      if (libraryId) {
        const documents = (await (
          await api.get(`/api/v1/libraries/${libraryId}/documents?size=100`)
        ).json()) as { items: Array<{ id: string }> };
        for (const document of documents.items) {
          await api.delete(
            `/api/v1/libraries/${libraryId}/documents/${document.id}`,
          );
        }
      }
    } finally {
      await api.dispose();
    }
    if (agentId) await deleteAgentViaApi(ADMIN, agentId);
    const cleanup = await apiAs(ADMIN);
    try {
      if (libraryId) await cleanup.delete(`/api/v1/libraries/${libraryId}`);
    } finally {
      await cleanup.dispose();
    }
    await restoreAgentSettings(agentsBefore);
    if (capabilityBefore && capabilityBefore !== "CAPABLE") {
      await setToolCapability(capabilityBefore);
    }
  });

  test("6a. Planbestätigung: der erste Lauf endet mit dem Plan, die Bestätigung startet einen neuen Lauf", async ({
    authenticatedPage: page,
  }) => {
    await startFreshChat(page);
    await askAgent(
      page,
      agentName,
      `Nenne die Fristen für „Wohngeld“ und für „Widersprüche“. (${SUFFIX})`,
    );

    const plan = answers(page).first();
    // The plan renders as a markdown ordered list; the numbering is a list marker, not text.
    await expect(
      plan.getByRole("listitem").filter({ hasText: /^Fristen für .+ suchen$/ }),
    ).toHaveText([
      "Fristen für Wohngeld suchen",
      "Fristen für Widersprüche suchen",
    ]);
    const confirmation = page.getByRole("group", {
      name: `Plan von ${agentName}`,
    });
    await expect(confirmation).toBeVisible();
    await expect(
      confirmation.getByRole("button", { name: "Ändern" }),
    ).toBeVisible();

    await confirmation.getByRole("button", { name: "Bestätigen" }).click();
    await expect(answers(page)).toHaveCount(2, { timeout: 30_000 });
    await expectAnswerSettled(page);
    const worked = answers(page).nth(1);
    await expect(worked).toContainText("Ergebnis des Agenten.");
    await expectCitedSource(page, FILE);
    await expect(
      page.getByRole("group", { name: `Plan von ${agentName}` }),
    ).toHaveCount(0);
  });

  test("6a. Planänderung: „Ändern“ wählt denselben Agenten und setzt den Fokus in die Eingabe", async ({
    authenticatedPage: page,
  }) => {
    await startFreshChat(page);
    await askAgent(
      page,
      agentName,
      `Nenne die Fristen für „Wohngeld“. (${SUFFIX}-ändern)`,
    );
    const confirmation = page.getByRole("group", {
      name: `Plan von ${agentName}`,
    });
    await expect(confirmation).toBeVisible();

    await page
      .getByTestId("chosen-agent-chip")
      // the icon's data-testid exists only in development builds of MUI
      .locator(".MuiChip-deleteIcon")
      .click();
    await expect(page.getByTestId("chosen-agent-chip")).toHaveCount(0);
    await confirmation.getByRole("button", { name: "Ändern" }).click();
    await expect(page.getByTestId("chosen-agent-chip")).toHaveText(
      `Agent: ${agentName}`,
    );
    await expect(
      page.getByRole("combobox", { name: "Nachricht eingeben" }),
    ).toBeFocused();
  });

  test("11. Agentenverzeichnis: wer das Recht hat, sieht alle Agenten ohne Nutzungsangaben", async ({
    regularUserPage: page,
  }) => {
    await page.goto("/agent-directory");
    await expect(page.getByText(/Agentenverzeichnis einsehen/)).toBeVisible();

    const admin = await apiAs(ADMIN);
    try {
      const granted = await admin.post(
        "/api/v1/admin/capabilities/VIEW_AGENT_DIRECTORY/grants",
        {
          data: { subjectType: "USER", subjectId: await accountId(MEMBER) },
        },
      );
      expect(granted.status(), await granted.text()).toBe(201);
      directoryGrantId = ((await granted.json()) as { id: string }).id;
    } finally {
      await admin.dispose();
    }

    await page.goto("/agent-directory");
    await expect(
      page.getByRole("heading", { name: "Agentenverzeichnis" }),
    ).toBeVisible();
    const table = page.getByRole("table", { name: "Agenten", exact: true });
    await expect(table.getByRole("link", { name: agentName })).toBeVisible();
    for (const header of [
      "Name",
      "Zweck",
      "Verantwortlich",
      "Fassung",
      "Gesperrt seit",
    ]) {
      await expect(
        table.getByRole("columnheader", { name: header }),
      ).toBeVisible();
    }
    await expect(
      page.getByText(/Angaben zur Nutzung enthält das Verzeichnis nicht/),
    ).toBeVisible();
    await expect(
      table.getByRole("columnheader", { name: /Nutzung|Läufe|Eigentümer/ }),
    ).toHaveCount(0);
    await table.getByRole("link", { name: agentName }).click();
    await expect(page.getByText("Recherche mit Planschritt")).toBeVisible();
  });
});
