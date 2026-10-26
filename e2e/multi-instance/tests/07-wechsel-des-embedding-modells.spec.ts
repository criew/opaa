import { expect, test } from "@playwright/test";
import {
  call,
  callOk,
  corpusLibrary,
  eventually,
  platform,
  prepareInstallation,
  type Session,
} from "../../fixtures/multiInstance";

/**
 * Scenario 7 (ADR-0061, Entscheidungen 3, 4 and 6): a change of the embedding model confirmed on
 * one instance holds on the other with its next request, a discard on the other ends it on both,
 * and a second confirmation while one waits for its switch is refused, whichever instance gets it.
 * The run of the change completes to the switch and both instances then search the new model.
 *
 * ai-stub embeds every input alike whatever the model name, so another name is a model change
 * with a re-embedding and the corpus stays searchable; the scenario returns to the original name
 * at the end.
 */

test.describe.configure({ mode: "serial" });

const WITHIN_MS = 5_000;
const RUN_TIMEOUT_MS = 180_000;

interface IndexState {
  stale: boolean;
  running: boolean;
  configured: { model?: string | null; dimensions: number };
  previous?: { model?: string | null } | null;
}

interface EmbeddingInfo {
  model: string;
  baseUrl?: string | null;
  dimensions: number;
  index?: IndexState | null;
}

let a = "";
let b = "";
let admin: Session;
let original: EmbeddingInfo;

const info = (url: string) =>
  callOk<EmbeddingInfo>(url, "GET", "/api/v1/admin/models/embedding-info", {
    token: admin.token,
  });

async function confirm(url: string, model: string) {
  const draft = { baseUrl: original.baseUrl, model };
  const probe = await callOk<{ dimensions: number; kind: string }>(
    url,
    "POST",
    "/api/v1/admin/models/embedding/probe",
    { token: admin.token, json: draft },
  );
  expect(probe.kind).toBe("MODEL_CHANGE");
  return call(url, "POST", "/api/v1/admin/models/embedding/change", {
    token: admin.token,
    json: { ...draft, expectedDimensions: probe.dimensions },
  });
}

async function switchedTo(url: string, model: string): Promise<boolean> {
  const current = await info(url);
  return current.model === model && current.index?.stale === false && !current.index.running;
}

test.beforeAll(async () => {
  a = await platform().instanceUrl("a");
  b = await platform().instanceUrl("b");
  admin = await prepareInstallation(a);
  // A few chunks, so the change has something to re-embed.
  await callOk(a, "POST", "/api/v1/libraries", {
    token: admin.token,
    json: corpusLibrary(`Embedding-Wechsel ${Date.now()}`),
  });
  original = await info(a);
});

test.afterAll(async () => {
  if (!original || (await switchedTo(a, original.model))) return;
  const current = await info(a);
  if (current.index?.stale) {
    await call(a, "DELETE", "/api/v1/admin/models/embedding/change", { token: admin.token });
  }
  if (!(await switchedTo(a, original.model))) {
    await confirm(a, original.model);
    await eventually(() => switchedTo(a, original.model), RUN_TIMEOUT_MS);
  }
});

test("ein auf A bestätigter Wechsel gilt sofort auf B, und B verwirft ihn für beide", async () => {
  const target = `${original.model}-a-${Date.now()}`;
  const confirmed = await confirm(a, target);
  expect(confirmed.status).toBe(200);

  expect(
    await eventually(async () => (await info(b)).index?.stale === true, WITHIN_MS),
  ).toBe(true);
  const onB = await info(b);
  expect(onB.model).toBe(target);
  expect(onB.index?.previous?.model).toBe(original.model);

  const second = await confirm(b, `${original.model}-b-${Date.now()}`);
  expect(second.status).toBe(409);

  await callOk(b, "DELETE", "/api/v1/admin/models/embedding/change", { token: admin.token });

  expect(
    await eventually(async () => (await info(a)).index?.stale === false, WITHIN_MS),
  ).toBe(true);
  expect((await info(a)).model).toBe(original.model);
});

test("ein auf B bestätigter Wechsel schaltet um, und beide Instanzen führen danach das neue Modell", async () => {
  const target = `${original.model}-b-${Date.now()}`;
  const confirmed = await confirm(b, target);
  expect(confirmed.status).toBe(200);

  expect(await eventually(() => switchedTo(a, target), RUN_TIMEOUT_MS)).toBe(true);
  expect(await eventually(() => switchedTo(b, target), WITHIN_MS)).toBe(true);
});
