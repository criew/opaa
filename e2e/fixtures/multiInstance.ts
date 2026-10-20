import { spawn, spawnSync, type ChildProcess } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * The two-instance target (ADR-0053, e2e/multi-instance/): one installation with two backend
 * instances A and B against one database, under Docker Compose or on kind. The scenarios address
 * A and B directly to tell them apart; everything that depends on the platform - restarting an
 * instance, the database, the rolling update, the load - goes through {@link platform}.
 *
 * Every value comes from the environment the orchestrator sets: scripts/run-e2e.mjs for Compose,
 * deploy/helm/ci/multi-instance-test.sh for kind.
 */

export type Instance = "a" | "b";

const env = (name: string, fallback?: string): string => {
  const value = process.env[name] ?? fallback;
  if (value === undefined || value === "") {
    throw new Error(`${name} ist nicht gesetzt; das Ziel multi-instance startet der Orchestrator.`);
  }
  return value;
};

export const bootstrapAdmin = {
  email: env("E2E_BOOTSTRAP_ADMIN_EMAIL", "systemverwaltung@opaa.example"),
  password: env("E2E_BOOTSTRAP_ADMIN_PASSWORD", "E2eMehrinstanzKonto!2026"),
};

/** Connections of the database pool of each instance (OPAA_DB_POOL_SIZE of the stack). */
export const databasePoolSize = Number(env("E2E_DB_POOL_SIZE", "5"));

/** Marker in the corpus objects and uploads whose embeddings ai-stub can hold back. */
export const HOLD_MARKER = "HALTEMARKE";

export const mailpitUrl = () => env("E2E_MAILPIT_BASE_URL");
export const aiStubUrl = () => env("E2E_AI_STUB_BASE_URL");
/** ai-stub as the backend reaches it, inside the platform's network. */
export const aiStubInternalUrl = () => env("E2E_AI_STUB_INTERNAL_URL", "http://ai-stub:8089");

/**
 * The S3 library over the bucket "corpus" of the stack's object store (`prefix` narrows it), as
 * the request body of `POST /api/v1/libraries`.
 */
export function corpusLibrary(name: string, prefix = ""): Record<string, unknown> {
  return {
    name,
    sourceType: "S3",
    sourceUrl: env("E2E_CORPUS_S3_URL", "http://upload-store:9000"),
    sourceCredentials: env("E2E_CORPUS_S3_CREDENTIALS", "opaa-uploads:OpaaUploads!2026"),
    sourceSettings: {
      pathStyle: true,
      scopes: [prefix ? { bucket: "corpus", prefix } : { bucket: "corpus" }],
    },
  };
}

// --- HTTP -------------------------------------------------------------------------------------

export interface Reply {
  status: number;
  body: unknown;
  headers: Headers;
}

/** A JSON call against one base URL; never throws on a status, only on a refused connection. */
export async function call(
  baseUrl: string,
  method: string,
  path: string,
  {
    token,
    json,
    form,
    headers = {},
  }: { token?: string; json?: unknown; form?: FormData; headers?: Record<string, string> } = {},
): Promise<Reply> {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(json !== undefined ? { "Content-Type": "application/json" } : {}),
      ...headers,
    },
    body: json !== undefined ? JSON.stringify(json) : form,
  });
  const text = await response.text();
  let body: unknown = text;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    // not JSON - kept as text for the failure message
  }
  return { status: response.status, body, headers: response.headers };
}

/** Like {@link call}, but fails with the body unless the status is one of `expected`. */
export async function callOk<T>(
  baseUrl: string,
  method: string,
  path: string,
  options: Parameters<typeof call>[3] = {},
  expected: number[] = [200, 201, 202, 204],
): Promise<T> {
  const reply = await call(baseUrl, method, path, options);
  if (!expected.includes(reply.status)) {
    throw new Error(
      `${method} ${baseUrl}${path} antwortete ${reply.status}: ${JSON.stringify(reply.body)}`,
    );
  }
  return reply.body as T;
}

/** Polls `probe` until it returns true, every 250 ms up to `timeoutMs`; returns the last value. */
export async function eventually(
  probe: () => Promise<boolean>,
  timeoutMs: number,
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  do {
    if (await probe()) {
      return true;
    }
    await sleep(250);
  } while (Date.now() < deadline);
  return probe();
}

export const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

// --- Sessions ---------------------------------------------------------------------------------

/** A local session: the access token plus the cookies logout needs (refresh, CSRF). */
export interface Session {
  token: string;
  cookies: Record<string, string>;
  passwordChangeRequired: boolean;
}

function cookiesOf(headers: Headers): Record<string, string> {
  const cookies: Record<string, string> = {};
  for (const line of headers.getSetCookie()) {
    const [pair] = line.split(";");
    const index = pair.indexOf("=");
    cookies[pair.slice(0, index).trim()] = pair.slice(index + 1).trim();
  }
  return cookies;
}

/** Signs in on the given instance; returns null on the uniform 401. */
export async function login(
  baseUrl: string,
  email: string,
  password: string,
): Promise<Session | null> {
  const reply = await call(baseUrl, "POST", "/api/v1/auth/local/login", {
    json: { email, password },
  });
  if (reply.status === 401) {
    return null;
  }
  if (reply.status !== 200) {
    throw new Error(`Anmeldung an ${baseUrl} antwortete ${reply.status}: ${JSON.stringify(reply.body)}`);
  }
  const body = reply.body as { accessToken: string; passwordChangeRequired: boolean };
  return {
    token: body.accessToken,
    cookies: cookiesOf(reply.headers),
    passwordChangeRequired: body.passwordChangeRequired,
  };
}

export async function loginOk(baseUrl: string, email: string, password: string): Promise<Session> {
  const session = await login(baseUrl, email, password);
  if (session === null) {
    throw new Error(`Anmeldung von ${email} an ${baseUrl} abgelehnt`);
  }
  return session;
}

/** Ends the session on the given instance the way the app does: cookies, CSRF header, bearer. */
export async function logout(baseUrl: string, session: Session): Promise<void> {
  const csrf =
    session.cookies["XSRF-TOKEN"] ??
    cookiesOf((await call(baseUrl, "GET", "/api/v1/auth/config")).headers)["XSRF-TOKEN"];
  const cookies = { ...session.cookies, "XSRF-TOKEN": csrf };
  await callOk(baseUrl, "POST", "/api/v1/auth/local/logout", {
    token: session.token,
    headers: {
      Cookie: Object.entries(cookies)
        .map(([name, value]) => `${name}=${value}`)
        .join("; "),
      "X-XSRF-TOKEN": csrf,
    },
  });
}

/** The status of an authenticated call - 200 while the session holds, 401 once it ended. */
export async function sessionStatus(baseUrl: string, token: string): Promise<number> {
  return (await call(baseUrl, "GET", "/api/v1/auth/me", { token })).status;
}

export function uniqueAddress(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}@stadt.example`;
}

/**
 * The installation state every scenario starts from: mail delivery to the stack's mailpit and
 * the local account management switched on. Idempotent; returns the administrator's session.
 */
export async function prepareInstallation(baseUrl: string): Promise<Session> {
  const admin = await loginOk(baseUrl, bootstrapAdmin.email, bootstrapAdmin.password);
  await callOk(baseUrl, "PUT", "/api/v1/system/mail-settings", {
    token: admin.token,
    json: {
      enabled: true,
      host: env("E2E_SMTP_HOST", "mailpit"),
      port: Number(env("E2E_SMTP_PORT", "1025")),
      encryption: "NONE",
      fromAddress: "opaa@opaa.example",
    },
  });
  await setLocalAccounts(baseUrl, admin.token, true);
  return admin;
}

/** Switches the local account management on or off, keeping every other setting. */
export async function setLocalAccounts(
  baseUrl: string,
  token: string,
  enabled: boolean,
): Promise<void> {
  const current = await callOk<Record<string, unknown>>(
    baseUrl,
    "GET",
    "/api/v1/admin/local-auth-settings",
    { token },
  );
  await callOk(baseUrl, "PUT", "/api/v1/admin/local-auth-settings", {
    token,
    json: {
      enabled,
      selfRegistrationEnabled: current.selfRegistrationEnabled,
      selfRegistrationAllowedDomains: current.selfRegistrationAllowedDomains,
      passwordResetEnabled: current.passwordResetEnabled,
      passwordMinLength: current.passwordMinLength,
      invitationTokenTtlHours: current.invitationTokenTtlHours,
      resetTokenTtlMinutes: current.resetTokenTtlMinutes,
      defaultExpiryDays: current.defaultExpiryDays,
      inactiveDays: current.inactiveDays,
    },
  });
}

/**
 * Creates an active local account and returns its id and a session, signed in on `signInUrl`
 * after the forced change of the generated initial password.
 */
export async function createActiveAccount(
  adminUrl: string,
  adminToken: string,
  signInUrl: string,
  email: string,
  password: string,
): Promise<{ id: string; session: Session }> {
  const created = await callOk<{ user: { id: string }; initialPassword: string }>(
    adminUrl,
    "POST",
    "/api/v1/admin/local-users",
    {
      token: adminToken,
      json: {
        email,
        displayName: email.split("@")[0],
        createdReason: "Erprobung mit zwei Instanzen",
        mode: "INITIAL_PASSWORD",
      },
    },
  );
  const first = await loginOk(signInUrl, email, created.initialPassword);
  const changed = await callOk<{ accessToken: string }>(
    signInUrl,
    "POST",
    "/api/v1/auth/local/change-password",
    { token: first.token, json: { currentPassword: created.initialPassword, newPassword: password } },
  );
  return {
    id: created.user.id,
    session: { token: changed.accessToken, cookies: first.cookies, passwordChangeRequired: false },
  };
}

// --- Mail -------------------------------------------------------------------------------------

/**
 * A further system administrator with its own address: every sign-in of the bootstrap
 * administrator puts one mail to it into the outbox (BOOTSTRAP_ACCOUNT_USED, ADR-0033), which makes
 * the sign-ins a counter of outbox mails.
 */
export async function createMailRecipient(url: string, adminToken: string): Promise<string> {
  const email = uniqueAddress("benachrichtigung");
  await callOk(url, "POST", "/api/v1/admin/local-users", {
    token: adminToken,
    json: {
      email,
      displayName: "Benachrichtigung",
      createdReason: "Erprobung mit zwei Instanzen",
      mode: "INITIAL_PASSWORD",
      systemRole: "SYSTEM_ADMIN",
    },
  });
  return email;
}

/** How many mails to `address` the mailpit holds. */
export async function mailCountTo(address: string): Promise<number> {
  const response = await fetch(
    `${mailpitUrl()}/api/v1/search?query=${encodeURIComponent(`to:${address}`)}&limit=1`,
  );
  const body = (await response.json()) as { messages_count: number };
  return body.messages_count;
}

/**
 * Waits until the mailpit holds `expected` mails to `address`, then `settleMs` longer for a second
 * delivery that must not come; returns the final count.
 */
export async function settledMailCount(
  address: string,
  expected: number,
  { timeoutMs = 120_000, settleMs = 15_000 } = {},
): Promise<number> {
  await eventually(async () => (await mailCountTo(address)) >= expected, timeoutMs);
  await sleep(settleMs);
  return mailCountTo(address);
}

// --- ai-stub ----------------------------------------------------------------------------------

/** Holds back every embedding whose input carries {@link HOLD_MARKER}, or releases them. */
export async function holdEmbeddings(hold: boolean): Promise<{ held: number }> {
  return callOk(aiStubUrl(), "POST", "/control/embedding-hold", {
    json: { hold, marker: HOLD_MARKER },
  });
}

export async function heldEmbeddings(): Promise<number> {
  return (await callOk<{ waiting: number }>(aiStubUrl(), "GET", "/control/embedding-hold"))
    .waiting;
}

// --- Platform ---------------------------------------------------------------------------------

/** Counts of the load generator (e2e/multi-instance/load.mjs) after it stopped. */
export interface LoadResult {
  requests: number;
  byOutcome: Record<string, number>;
  failures: { at: string; path: string; outcome: string }[];
  /** Sign-ins of the bootstrap administrator, each of which queued one mail per recipient. */
  notifyingSignIns: number;
  signInFailures: { at: string; outcome: string }[];
  longestGapMs: number;
}

export interface Platform {
  readonly name: "compose" | "kind";
  /** Base URL of the frontend's nginx, as the browser reaches it. */
  frontendUrl(): Promise<string>;
  /** Base URL of one backend instance itself. */
  instanceUrl(instance: Instance): Promise<string>;
  /** Restarts one instance and waits until it is ready again. */
  restart(instance: Instance): Promise<void>;
  /** One SQL statement against the installation's database, rows tab-separated. */
  sql(statement: string): Promise<string>;
  /**
   * Starts one more instance whose environment differs in `overrides` and returns its exit code
   * and log once it ended (or null for the code if it was still running after the deadline).
   */
  startDeviatingInstance(
    overrides: Record<string, string>,
  ): Promise<{ exitCode: number | null; log: string }>;
  /** Starts the load generator (e2e/multi-instance/load.mjs) inside the platform's network. */
  startLoad(token: string, runId: string): Promise<void>;
  stopLoad(): Promise<LoadResult>;
  /**
   * The update of the backend to the next version in the documented order: migration step, then
   * one instance after the other. On kind the chart rolls the frontend in the same upgrade.
   */
  rollingUpdate(): Promise<void>;
  /** Replaces the frontend by its next version where that is a step of its own; false otherwise. */
  replaceFrontend(): Promise<boolean>;
  /** A further restart of every instance under load, where the platform offers one. */
  restartAll(): Promise<boolean>;
}

function run(command: string, args: string[], { allowFailure = false, input = undefined as string | undefined } = {}): string {
  const result = spawnSync(command, args, { encoding: "utf8", input, maxBuffer: 64 * 1024 * 1024 });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0 && !allowFailure) {
    throw new Error(
      `${command} ${args.join(" ")} endete mit ${result.status}:\n${result.stdout}\n${result.stderr}`,
    );
  }
  return `${result.stdout ?? ""}${allowFailure ? (result.stderr ?? "") : ""}`;
}

/** Runs a command without blocking the event loop; resolves with its trimmed output. */
function runAsync(command: string, args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => (stdout += chunk));
    child.stderr.on("data", (chunk) => (stderr += chunk));
    child.on("error", reject);
    child.on("close", (code) =>
      code === 0
        ? resolve(stdout.trim())
        : reject(new Error(`${command} ${args.join(" ")} endete mit ${code}:\n${stderr}`)),
    );
  });
}

async function waitFor(probe: () => boolean | Promise<boolean>, timeoutMs: number, what: string) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await probe()) {
      return;
    }
    await sleep(2000);
  }
  throw new Error(`${what}: nach ${timeoutMs / 1000} s nicht erreicht`);
}

const loadResultMarker = "LOAD_RESULT ";

function parseLoadResult(log: string): LoadResult {
  const line = log.split("\n").find((entry) => entry.startsWith(loadResultMarker));
  if (!line) {
    throw new Error(`Der Lastgenerator hat kein Ergebnis geschrieben:\n${log}`);
  }
  return JSON.parse(line.slice(loadResultMarker.length)) as LoadResult;
}

const loadScript = () =>
  readFileSync(fileURLToPath(new URL("../multi-instance/load.mjs", import.meta.url)), "utf8");

class ComposePlatform implements Platform {
  readonly name = "compose";
  private readonly composeArgs: string[] = JSON.parse(env("E2E_COMPOSE_ARGS"));
  private readonly project = env("E2E_COMPOSE_PROJECT");
  private readonly loadContainer = `${env("E2E_COMPOSE_PROJECT")}-load`;

  async frontendUrl() {
    return env("E2E_BASE_URL");
  }

  async instanceUrl(instance: Instance) {
    return env(instance === "a" ? "E2E_BACKEND_A_URL" : "E2E_BACKEND_B_URL");
  }

  private compose(args: string[], options: { allowFailure?: boolean } = {}) {
    return run("docker", [...this.composeArgs, ...args], options);
  }

  private healthy(service: string) {
    const id = this.compose(["ps", "-q", service]).trim();
    return id !== "" && run("docker", ["inspect", "-f", "{{.State.Health.Status}}", id]).trim() === "healthy";
  }

  async restart(instance: Instance) {
    const service = `backend-${instance}`;
    this.compose(["restart", service]);
    await waitFor(() => this.healthy(service), 300_000, `${service} gesund nach Neustart`);
  }

  sql(statement: string) {
    return runAsync("docker", [
      ...this.composeArgs,
      "exec",
      "-T",
      "postgres",
      "psql",
      "-U",
      "opaa",
      "-d",
      "opaa",
      "-At",
      "-F",
      "\t",
      "-c",
      statement,
    ]);
  }

  async startDeviatingInstance(overrides: Record<string, string>) {
    const name = `${this.project}-deviating`;
    const args = ["run", "--rm", "--no-deps", "--name", name];
    for (const [key, value] of Object.entries(overrides)) {
      args.push("-e", `${key}=${value}`);
    }
    args.push("backend-a");
    const result = spawnSync("docker", [...this.composeArgs, ...args], {
      encoding: "utf8",
      timeout: 300_000,
      maxBuffer: 64 * 1024 * 1024,
    });
    if (result.status === null) {
      run("docker", ["rm", "-f", name], { allowFailure: true });
    }
    return { exitCode: result.status, log: `${result.stdout}${result.stderr}` };
  }

  async startLoad(token: string, runId: string) {
    const dir = mkdtempSync(join(tmpdir(), "opaa-load-"));
    writeFileSync(join(dir, "load.mjs"), loadScript());
    run("docker", ["rm", "-f", this.loadContainer], { allowFailure: true });
    run("docker", [
      "run",
      "-d",
      "--name",
      this.loadContainer,
      "--network",
      `${this.project}_default`,
      "-v",
      `${dir}:/load:ro`,
      "-e",
      "BASE=http://frontend:8080",
      "-e",
      `TOKEN=${token}`,
      "-e",
      `RUN_ID=${runId}`,
      "-e",
      `ADMIN_EMAIL=${bootstrapAdmin.email}`,
      "-e",
      `ADMIN_PASSWORD=${bootstrapAdmin.password}`,
      "node:24-alpine",
      "node",
      "/load/load.mjs",
    ]);
  }

  async stopLoad() {
    run("docker", ["exec", this.loadContainer, "touch", "/tmp/stop"]);
    run("docker", ["wait", this.loadContainer]);
    const log = run("docker", ["logs", this.loadContainer], { allowFailure: true });
    run("docker", ["rm", "-f", this.loadContainer], { allowFailure: true });
    return parseLoadResult(log);
  }

  async rollingUpdate() {
    // docker-compose.multi.yml, "Update without downtime": migration step, then one instance after
    // the other, each until healthy.
    const next = { OPAA_MULTI_BACKEND_IMAGE: env("E2E_NEXT_BACKEND_IMAGE") };
    const withNext = (args: string[]) => {
      const result = spawnSync("docker", [...this.composeArgs, ...args], {
        encoding: "utf8",
        env: { ...process.env, ...next },
        maxBuffer: 64 * 1024 * 1024,
      });
      if (result.status !== 0) {
        throw new Error(`docker compose ${args.join(" ")} endete mit ${result.status}:\n${result.stdout}\n${result.stderr}`);
      }
    };
    withNext(["run", "--rm", "backend-migrate"]);
    withNext(["up", "-d", "--no-deps", "--wait", "backend-a"]);
    await sleep(15_000);
    withNext(["up", "-d", "--no-deps", "--wait", "backend-b"]);
  }

  async replaceFrontend() {
    const result = spawnSync(
      "docker",
      [...this.composeArgs, "up", "-d", "--no-deps", "--wait", "frontend"],
      {
        encoding: "utf8",
        env: { ...process.env, OPAA_MULTI_FRONTEND_IMAGE: env("E2E_NEXT_FRONTEND_IMAGE") },
      },
    );
    if (result.status !== 0) {
      throw new Error(`Austausch des Frontends endete mit ${result.status}:\n${result.stderr}`);
    }
    return true;
  }

  async restartAll() {
    return false;
  }
}

/** A `kubectl port-forward` kept alive for one target, restarted when its pod went away. */
class Forward {
  private process: ChildProcess | null = null;
  private target = "";

  constructor(
    private readonly namespace: string,
    private readonly localPort: number,
    private readonly remotePort: number,
  ) {}

  async to(target: string, readyPath: string): Promise<string> {
    const url = `http://127.0.0.1:${this.localPort}`;
    if (this.target === target && this.process && this.process.exitCode === null) {
      try {
        await fetch(`${url}${readyPath}`);
        return url;
      } catch {
        // the pod behind it went away - start again
      }
    }
    this.close();
    this.target = target;
    this.process = spawn(
      "kubectl",
      ["-n", this.namespace, "port-forward", target, `${this.localPort}:${this.remotePort}`],
      { stdio: "ignore" },
    );
    const pidFile = process.env.E2E_KIND_FORWARD_PIDS;
    if (pidFile && this.process.pid) {
      writeFileSync(pidFile, `${this.process.pid}\n`, { flag: "a" });
    }
    await waitFor(
      async () => {
        try {
          await fetch(`${url}${readyPath}`);
          return true;
        } catch {
          return false;
        }
      },
      60_000,
      `port-forward ${target}`,
    );
    return url;
  }

  close() {
    if (this.process && this.process.exitCode === null) {
      this.process.kill();
    }
    this.process = null;
  }
}

class KindPlatform implements Platform {
  readonly name = "kind";
  private readonly namespace = env("E2E_KIND_NAMESPACE");
  private readonly testNamespace = env("E2E_KIND_TEST_NAMESPACE");
  private readonly release = env("E2E_KIND_RELEASE");
  private readonly forwards = {
    a: new Forward(env("E2E_KIND_NAMESPACE"), 18183, 8080),
    b: new Forward(env("E2E_KIND_NAMESPACE"), 18184, 8080),
    frontend: new Forward(env("E2E_KIND_NAMESPACE"), Number(env("E2E_KIND_FRONTEND_PORT", "18180")), 80),
  };
  /** The pod names that stand for A and B; A keeps its pod, a restarted B gets the new one. */
  private pods: Record<Instance, string> = { a: "", b: "" };

  constructor() {
    process.on("exit", () => Object.values(this.forwards).forEach((forward) => forward.close()));
  }

  private kubectl(args: string[], options: { allowFailure?: boolean; input?: string } = {}) {
    return run("kubectl", ["-n", this.namespace, ...args], options);
  }

  private readyBackendPods(): string[] {
    const listing = this.kubectl([
      "get",
      "pods",
      "-l",
      "app.kubernetes.io/component=backend",
      "-o",
      'jsonpath={range .items[*]}{.metadata.name}|{.metadata.deletionTimestamp}|{.status.conditions[?(@.type=="Ready")].status}{"\\n"}{end}',
    ]);
    return listing
      .split("\n")
      .map((line) => line.trim().split("|"))
      .filter((fields) => fields.length === 3 && fields[1] === "" && fields[2] === "True")
      .map((fields) => fields[0])
      .sort();
  }

  private assignPods() {
    const ready = this.readyBackendPods();
    for (const instance of ["a", "b"] as Instance[]) {
      if (!ready.includes(this.pods[instance])) {
        this.pods[instance] = ready.find((pod) => !Object.values(this.pods).includes(pod)) ?? "";
      }
    }
    if (!this.pods.a || !this.pods.b) {
      throw new Error(`Nicht zwei bereite Backend-Pods: ${ready.join(", ")}`);
    }
  }

  async frontendUrl() {
    return this.forwards.frontend.to(`svc/${this.release}-frontend`, "/api/health");
  }

  async instanceUrl(instance: Instance) {
    this.assignPods();
    return this.forwards[instance].to(`pod/${this.pods[instance]}`, "/api/health");
  }

  async restart(instance: Instance) {
    this.assignPods();
    const old = this.pods[instance];
    this.forwards[instance].close();
    this.kubectl(["delete", "pod", old, "--wait=true", "--timeout=300s"]);
    await waitFor(
      () => {
        const ready = this.readyBackendPods();
        return ready.length === 2 && !ready.includes(old);
      },
      300_000,
      `Ersatz für ${old} bereit`,
    );
    this.pods[instance] = "";
  }

  sql(statement: string) {
    return runAsync("kubectl", [
      "-n",
      this.namespace,
      "exec",
      `${this.release}-postgresql-0`,
      "--",
      "psql",
      "-U",
      "opaa",
      "-d",
      "opaa",
      "-At",
      "-F",
      "\t",
      "-c",
      statement,
    ]);
  }

  async startDeviatingInstance(overrides: Record<string, string>) {
    // A pod from the backend's own template, with the overrides on its main container.
    const deployment = JSON.parse(this.kubectl(["get", "deployment", `${this.release}-backend`, "-o", "json"]));
    const template = deployment.spec.template;
    const container = template.spec.containers[0];
    container.env = [
      ...(container.env ?? []).filter((entry: { name: string }) => !(entry.name in overrides)),
      ...Object.entries(overrides).map(([name, value]) => ({ name, value })),
    ];
    // The template's labels let the network policies admit the pod to the database; its probes
    // keep it out of the service, and without pod-template-hash no ReplicaSet adopts it.
    const name = `${this.release}-deviating`;
    const pod = {
      apiVersion: "v1",
      kind: "Pod",
      metadata: { name, labels: template.metadata.labels },
      spec: { ...template.spec, restartPolicy: "Never" },
    };
    this.kubectl(["delete", "pod", name, "--ignore-not-found", "--wait=true"]);
    this.kubectl(["apply", "-f", "-"], { input: JSON.stringify(pod) });
    let exitCode: number | null = null;
    const deadline = Date.now() + 300_000;
    while (Date.now() < deadline) {
      const phase = this.kubectl(["get", "pod", name, "-o", "jsonpath={.status.phase}"]).trim();
      if (phase === "Failed" || phase === "Succeeded") {
        exitCode = Number(
          this.kubectl([
            "get",
            "pod",
            name,
            "-o",
            "jsonpath={.status.containerStatuses[0].state.terminated.exitCode}",
          ]).trim(),
        );
        break;
      }
      await sleep(2000);
    }
    const log = this.kubectl(["logs", name, "-c", container.name], { allowFailure: true });
    this.kubectl(["delete", "pod", name, "--ignore-not-found", "--wait=false"]);
    return { exitCode, log };
  }

  async startLoad(token: string, runId: string) {
    run("kubectl", ["-n", this.testNamespace, "delete", "pod", "load", "--ignore-not-found", "--wait=true"]);
    run("kubectl", ["-n", this.testNamespace, "delete", "configmap", "load-script", "--ignore-not-found"]);
    run("kubectl", ["-n", this.testNamespace, "create", "configmap", "load-script", "--from-literal", `load.mjs=${loadScript()}`]);
    const pod = {
      apiVersion: "v1",
      kind: "Pod",
      metadata: { name: "load" },
      spec: {
        restartPolicy: "Never",
        containers: [
          {
            name: "load",
            image: "node:24-alpine",
            command: ["node", "/load/load.mjs"],
            env: [
              { name: "BASE", value: `http://${this.release}-frontend.${this.namespace}.svc` },
              { name: "TOKEN", value: token },
              { name: "RUN_ID", value: runId },
              { name: "ADMIN_EMAIL", value: bootstrapAdmin.email },
              { name: "ADMIN_PASSWORD", value: bootstrapAdmin.password },
            ],
            volumeMounts: [{ name: "script", mountPath: "/load" }],
          },
        ],
        volumes: [{ name: "script", configMap: { name: "load-script" } }],
      },
    };
    run("kubectl", ["-n", this.testNamespace, "apply", "-f", "-"], { input: JSON.stringify(pod) });
    run("kubectl", ["-n", this.testNamespace, "wait", "--for=condition=Ready", "pod/load", "--timeout=180s"]);
  }

  async stopLoad() {
    run("kubectl", ["-n", this.testNamespace, "exec", "load", "--", "touch", "/tmp/stop"]);
    await waitFor(
      () =>
        ["Succeeded", "Failed"].includes(
          run("kubectl", ["-n", this.testNamespace, "get", "pod", "load", "-o", "jsonpath={.status.phase}"]).trim(),
        ),
      120_000,
      "Lastgenerator beendet",
    );
    const log = run("kubectl", ["-n", this.testNamespace, "logs", "load"]);
    run("kubectl", ["-n", this.testNamespace, "delete", "pod", "load", "--wait=false"]);
    return parseLoadResult(log);
  }

  async rollingUpdate() {
    run("helm", JSON.parse(env("E2E_KIND_UPGRADE_ARGS")));
    this.kubectl(["rollout", "status", `deployment/${this.release}-backend`, "--timeout=15m"]);
    this.kubectl(["rollout", "status", `deployment/${this.release}-frontend`, "--timeout=5m"]);
    this.pods = { a: "", b: "" };
  }

  async replaceFrontend() {
    return false;
  }

  async restartAll() {
    this.kubectl(["rollout", "restart", `deployment/${this.release}-backend`]);
    this.kubectl(["rollout", "status", `deployment/${this.release}-backend`, "--timeout=15m"]);
    this.pods = { a: "", b: "" };
    return true;
  }
}

let current: Platform | null = null;

/** The platform of this run, from E2E_MULTI_PLATFORM. */
export function platform(): Platform {
  if (current === null) {
    current = env("E2E_MULTI_PLATFORM") === "kind" ? new KindPlatform() : new ComposePlatform();
  }
  return current;
}
