import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import {
  askQuestion,
  createLibraryWithDocument,
  createLibraryWithDocuments,
  expectCitedSource,
  openNewLibraryWizard,
  startFreshChat,
} from "../../fixtures/chat";
import { signInSuccessfully } from "../../fixtures/localAuth";
import { callOk, eventually, loginOk } from "../../fixtures/multiInstance";

/**
 * The journey of an installation without internet access (scripts/offline.mjs): sign-in, a
 * library with an uploaded document, a question answered with the document as its source, and the
 * pipelines of further formats, whose parsing libraries are invisible to the build guard. The
 * stack has no route out, and two recorders watch for attempts to leave it - egress-dns for every
 * name outside the stack, the browser context for every request to another origin. A deliberate
 * lookup by the backend proves the first recorder works; apart from it both stay empty.
 */

const here = dirname(fileURLToPath(import.meta.url));
const FIXTURE_DIR = join(here, "..", "..", "..", "demo", "seed", "e2e-data", "test-documents");
const DOCUMENT_PATH = join(FIXTURE_DIR, "wissensdokument.txt");
const DOCUMENT_NAME = "wissensdokument.txt";
/** Formats with their own parsing library; the mail recurses into the pipeline of its attachment. */
const FORMAT_DOCUMENTS = ["formatdokument.xlsx", "formatdokument.html", "formatdokument.eml"];
/** A name outside the stack the backend is made to look up; reserved, never resolvable. */
const PROBE_HOST = "egress-probe.offline-check.example";
/** The probe name, also with a search domain the host's resolver configuration appends. */
const isProbe = (name: string) => name === PROBE_HOST || name.startsWith(`${PROBE_HOST}.`);

const baseUrl = process.env.E2E_BASE_URL ?? "http://localhost:13003";
const egressDnsUrl = process.env.E2E_EGRESS_DNS_URL ?? "http://localhost:18053";
const admin = {
  email: process.env.E2E_BOOTSTRAP_ADMIN_EMAIL ?? "",
  password: process.env.E2E_BOOTSTRAP_ADMIN_PASSWORD ?? "",
};

interface DnsQuery {
  at: string;
  name: string;
  type: string;
  from: string;
}

async function recordedQueries(): Promise<DnsQuery[]> {
  const response = await fetch(`${egressDnsUrl}/queries`);
  expect(response.ok, "Protokoll von egress-dns nicht lesbar").toBe(true);
  return (await response.json()) as DnsQuery[];
}

test.describe.serial("Betrieb ohne Internetzugang", () => {
  let context: BrowserContext;
  let page: Page;
  const foreignRequests: string[] = [];
  const libraryName = `Ohne-Internet-${Date.now().toString(36)}`;
  const formatLibraryName = `${libraryName}-Formate`;

  test.beforeAll(async ({ browser }) => {
    context = await browser.newContext();
    // Everything the browser loads must come from the installation itself: fonts, scripts, images.
    const origin = new URL(baseUrl).origin;
    await context.route("**/*", async (route) => {
      const url = route.request().url();
      if (new URL(url).origin === origin) {
        await route.continue();
        return;
      }
      foreignRequests.push(url);
      await route.abort("blockedbyclient");
    });
    page = await context.newPage();
  });

  test.afterAll(async () => {
    await context.close();
  });

  test("Anmeldung mit einem lokalen Konto", async () => {
    await signInSuccessfully(page, admin.email, admin.password, { route: "/login/system" });
    await expect(page).toHaveURL(/\/chat/);
  });

  test("Bibliothek anlegen und Dokument aufnehmen", async () => {
    await createLibraryWithDocument(page, libraryName, DOCUMENT_PATH, DOCUMENT_NAME);
  });

  test("Frage mit Antwort und Fundstelle", async () => {
    // A space searches only the knowledge assigned to it; the assignment is a precondition here.
    const { token } = await loginOk(baseUrl, admin.email, admin.password);
    const spaces = await callOk<{ id: string; isDefault: boolean }[]>(baseUrl, "GET", "/api/v1/spaces", { token });
    const libraries = await callOk<{ id: string; name: string }[]>(baseUrl, "GET", "/api/v1/libraries", { token });
    const space = spaces.find((candidate) => candidate.isDefault);
    const library = libraries.find((candidate) => candidate.name === libraryName);
    expect(space && library, "Standard-Space oder Bibliothek fehlt").toBeTruthy();
    await callOk(baseUrl, "POST", `/api/v1/spaces/${space!.id}/assets`, {
      token,
      json: { assetType: "KNOWLEDGE_LIBRARY", assetId: library!.id },
    });

    await startFreshChat(page);
    await askQuestion(page, "Was steht im Wissensdokument?");
    await expectCitedSource(page, DOCUMENT_NAME);
  });

  test("weitere Formate aufnehmen", async () => {
    // A library of its own, assigned to no space: the question above stays answered from one
    // document, while these uploads still run every format pipeline inside the closed network.
    await createLibraryWithDocuments(
      page,
      formatLibraryName,
      FORMAT_DOCUMENTS.map((name) => ({ path: join(FIXTURE_DIR, name), name })),
    );
    // The mail's attachment is its own indexed row: expanding the group shows a fourth chip.
    const toggle = page.getByRole("button", { name: "Anhänge von formatdokument.eml anzeigen" });
    await expect(toggle).toHaveText(/1 Anhang/);
    await toggle.click();
    await expect(page.getByText("formatdokument-anhang.txt", { exact: true })).toBeVisible();
    await expect(page.getByText("indiziert", { exact: true })).toHaveCount(4, { timeout: 30_000 });
  });

  test("ein Verbindungsversuch nach außen wird erkannt", async () => {
    await openNewLibraryWizard(page);
    await page.getByRole("radio", { name: /Webverzeichnis/ }).click();
    await page.getByRole("button", { name: "Weiter", exact: true }).click();
    await page.getByLabel("Adresse (URL)").fill(`http://${PROBE_HOST}/`);
    await page.getByRole("button", { name: "Verbindung testen" }).click();
    await expect(
      page.getByRole("alert").filter({ hasText: "Der Host konnte nicht gefunden werden" }),
    ).toBeVisible({ timeout: 30_000 });

    const seen = await eventually(
      async () => (await recordedQueries()).some((query) => isProbe(query.name)),
      15_000,
    );
    expect(seen, `egress-dns hat die Abfrage von ${PROBE_HOST} nicht aufgezeichnet`).toBe(true);
  });

  test("sonst keine Verbindung nach außen", async () => {
    const session = await loginOk(baseUrl, admin.email, admin.password);
    const disclosure = await callOk<{ instance: { instances: { version: string }[] } }>(
      baseUrl,
      "GET",
      "/api/v1/admin/compliance/data-collection-disclosure",
      { token: session.token },
    );
    const queries = (await recordedQueries()).filter((query) => !isProbe(query.name));
    const result = {
      date: new Date().toISOString(),
      versions: [...new Set(disclosure.instance.instances.map((instance) => instance.version))],
      foreignDnsQueries: queries,
      foreignBrowserRequests: foreignRequests,
    };
    const resultsDir = join(here, "..", "..", "test-results");
    mkdirSync(resultsDir, { recursive: true });
    writeFileSync(join(resultsDir, "offline-result.json"), `${JSON.stringify(result, null, 2)}\n`);

    expect(queries, "Namensauflösungen außerhalb des Stapels").toEqual([]);
    expect(foreignRequests, "Abrufe des Browsers außerhalb der Installation").toEqual([]);
  });
});
