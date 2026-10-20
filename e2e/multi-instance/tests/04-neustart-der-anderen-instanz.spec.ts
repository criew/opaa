import { expect, test } from "@playwright/test";
import {
  HOLD_MARKER,
  callOk,
  corpusLibrary,
  eventually,
  heldEmbeddings,
  holdEmbeddings,
  platform,
  prepareInstallation,
  sleep,
  type Session,
} from "../../fixtures/multiInstance";

/**
 * Scenario 4 (ADR-0053, Entscheidung 8): an indexing run and an upload of instance A survive a
 * restart of instance B. B's start sweeps runs and uploads of dead instances only; A stays alive
 * by its heartbeat, so B must leave both alone. ai-stub holds A's embeddings back until B is ready
 * again, which keeps both in flight across the whole restart.
 */

let a = "";
let b = "";
let admin: Session;

test.beforeAll(async () => {
  a = await platform().instanceUrl("a");
  b = await platform().instanceUrl("b");
  admin = await prepareInstallation(a);
});

test.afterAll(async () => {
  await holdEmbeddings(false);
});

interface Status {
  status: string;
  documentsIndexedTotal: number;
  documentsFailed: number;
  totalDocuments: number;
}

test("Indexierungslauf und Upload von A überstehen den Neustart von B", async () => {
  const corpusSize = Number(process.env.E2E_CORPUS_SIZE ?? "40");
  const library = await callOk<{ id: string }>(a, "POST", "/api/v1/libraries", {
    token: admin.token,
    json: corpusLibrary(`Neustart ${Date.now()}`),
  });
  const uploads = await callOk<{ id: string }>(a, "POST", "/api/v1/libraries", {
    token: admin.token,
    json: { name: `Upload Neustart ${Date.now()}`, sourceType: "UPLOAD" },
  });

  await holdEmbeddings(true);
  await callOk(a, "POST", `/api/v1/libraries/${library.id}/indexing`, { token: admin.token });
  const form = new FormData();
  form.append(
    "file",
    new Blob([`Aktennotiz während des Neustarts, ${HOLD_MARKER}.\n`], { type: "text/plain" }),
    "aktennotiz.txt",
  );
  const document = await callOk<{ id: string; status: string }>(
    a,
    "POST",
    `/api/v1/libraries/${uploads.id}/documents`,
    { token: admin.token, form },
  );
  expect(document.status).toBe("PENDING");
  // Both are in flight on A: their embedding calls wait at ai-stub.
  expect(await eventually(async () => (await heldEmbeddings()) >= 2, 60_000)).toBe(true);

  await platform().restart("b");
  const restartedB = await platform().instanceUrl("b");

  // B is up again; A's run and upload are untouched and still owned by A.
  const status = await callOk<Status>(
    restartedB,
    "GET",
    `/api/v1/libraries/${library.id}/indexing/status`,
    { token: admin.token },
  );
  expect(status.status).toBe("RUNNING");
  expect(await documentStatus(restartedB, uploads.id, document.id)).toBe("PENDING");

  await holdEmbeddings(false);

  const deadline = Date.now() + 300_000;
  let final = status;
  while (Date.now() < deadline) {
    final = await callOk<Status>(a, "GET", `/api/v1/libraries/${library.id}/indexing/status`, {
      token: admin.token,
    });
    if (final.status !== "RUNNING") {
      break;
    }
    await sleep(2000);
  }
  expect(final.status, JSON.stringify(final)).toBe("COMPLETED");
  expect(final.documentsFailed).toBe(0);
  expect(final.documentsIndexedTotal).toBe(corpusSize);

  const runs = await callOk<{ runs: { status: string }[] }>(
    b,
    "GET",
    `/api/v1/libraries/${library.id}/indexing/runs`,
    { token: admin.token },
  );
  expect(runs.runs.map((run) => run.status)).toEqual(["COMPLETED"]);

  expect(
    await eventually(
      async () => (await documentStatus(b, uploads.id, document.id)) === "INDEXED",
      120_000,
    ),
  ).toBe(true);
});

async function documentStatus(url: string, libraryId: string, documentId: string) {
  const page = await callOk<{ items: { id: string; status: string }[] }>(
    url,
    "GET",
    `/api/v1/libraries/${libraryId}/documents?size=100`,
    { token: admin.token },
  );
  return page.items.find((item) => item.id === documentId)?.status;
}
