import { expect, test } from "@playwright/test";
import {
  bootstrapAdmin,
  callOk,
  createMailRecipient,
  loginOk,
  platform,
  prepareInstallation,
  settledMailCount,
  sleep,
  type LoadResult,
} from "../../fixtures/multiInstance";

/**
 * Scenario 5 (ADR-0053, Entscheidungen 4, 11, 13 to 15): the orchestrator started the installation
 * with the previous version; the update to the current one runs in the documented order while a
 * load generator reads through the frontend and signs the bootstrap administrator in, which puts a
 * mail into the outbox every time. No request may fail and every one of those mails must leave,
 * exactly once - also those an instance accepted shortly before it was replaced.
 */

const versionsOf = async (url: string, token: string) => {
  const disclosure = await callOk<{ instance: { instances: { version: string }[] } }>(
    url,
    "GET",
    "/api/v1/admin/compliance/data-collection-disclosure",
    { token },
  );
  return disclosure.instance.instances.map((instance) => instance.version).sort();
};

function report(phase: string, result: LoadResult) {
  const summary = JSON.stringify({
    requests: result.requests,
    byOutcome: result.byOutcome,
    longestGapMs: result.longestGapMs,
    notifyingSignIns: result.notifyingSignIns,
    failures: result.failures.slice(0, 20),
  });
  test.info().annotations.push({ type: phase, description: summary });
  console.log(`${phase}: ${summary}`);
  return summary;
}

test("Rolling Update von n−1 auf n unter Last ohne Fehler und ohne verlorene Mail", async () => {
  const a = await platform().instanceUrl("a");
  const admin = await prepareInstallation(a);
  const recipient = await createMailRecipient(a, admin.token);
  const before = await versionsOf(a, admin.token);
  expect(before).toHaveLength(2);
  let mails = 0;

  await platform().startLoad(admin.token, "backend");
  // Load before the first instance goes away, and after the last one is back.
  await sleep(10_000);
  await platform().rollingUpdate();
  await sleep(10_000);
  const backend = await platform().stopLoad();
  const backendSummary = report("Last beim Austausch der Backends", backend);
  expect(backend.requests, backendSummary).toBeGreaterThan(100);
  expect(backend.failures, backendSummary).toEqual([]);
  expect(backend.notifyingSignIns, backendSummary).toBeGreaterThan(10);
  mails += backend.notifyingSignIns;

  const freshA = await platform().instanceUrl("a");
  const freshB = await platform().instanceUrl("b");
  const after = await versionsOf(freshA, admin.token);
  expect(after).toHaveLength(2);
  expect(await versionsOf(freshB, admin.token)).toEqual(after);
  if (process.env.E2E_EXPECT_VERSION_CHANGE === "true") {
    expect(after.some((version) => before.includes(version)), `${before} -> ${after}`).toBe(false);
  }

  await platform().startLoad(admin.token, "second");
  await sleep(5_000);
  if (await platform().replaceFrontend()) {
    // Compose replaces its one frontend container on its own: no request may fail with a status;
    // only the seconds without a listening port are tolerated, and reported.
    await sleep(10_000);
    const frontend = await platform().stopLoad();
    const summary = report("Last beim Austausch des Frontends", frontend);
    const answered = frontend.failures.filter((failure) => !failure.outcome.startsWith("ERR:"));
    expect(answered, summary).toEqual([]);
    expect(frontend.longestGapMs, summary).toBeLessThan(10_000);
    mails += frontend.notifyingSignIns;
  } else {
    // On kind the upgrade rolled the frontend already; a restart of every backend pod follows.
    expect(await platform().restartAll()).toBe(true);
    await sleep(10_000);
    const restart = await platform().stopLoad();
    const summary = report("Last beim Neustart aller Backend-Pods", restart);
    expect(restart.failures, summary).toEqual([]);
    mails += restart.notifyingSignIns;
  }

  expect(await settledMailCount(recipient, mails)).toBe(mails);
  expect(await platform().sql("SELECT count(*) FROM mail_outbox")).toBe("0");

  // The administrator signs in through the frontend of the new version.
  await loginOk(await platform().frontendUrl(), bootstrapAdmin.email, bootstrapAdmin.password);
});
