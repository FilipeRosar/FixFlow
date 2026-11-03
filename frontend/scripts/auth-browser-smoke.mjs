import fs from "node:fs/promises";
const origin = process.env.FIXFLOW_E2E_ORIGIN ?? "http://localhost:4200";
const ws = new WebSocket(
  process.env.FIXFLOW_BIDI_URL ?? "ws://127.0.0.1:9222/session",
);
await new Promise((resolve, reject) => {
  ws.addEventListener("open", resolve);
  ws.addEventListener("error", reject);
});
let id = 0;
const pending = new Map();
ws.addEventListener("message", (event) => {
  const value = JSON.parse(event.data);
  if (value.id) {
    const entry = pending.get(value.id);
    if (!entry) return;
    pending.delete(value.id);
    value.type === "error"
      ? entry.reject(new Error(value.message))
      : entry.resolve(value.result);
  }
});
function call(method, params = {}) {
  return new Promise((resolve, reject) => {
    const key = ++id;
    pending.set(key, { resolve, reject });
    ws.send(JSON.stringify({ id: key, method, params }));
  });
}
let context;
async function evaluate(expression) {
  const result = await call("script.evaluate", {
    expression,
    target: { context },
    awaitPromise: true,
  });
  if (result.type === "exception")
    throw new Error(result.exceptionDetails.text);
  return result.result?.value;
}
async function waitFor(expression) {
  for (let i = 0; i < 80; i++) {
    if (await evaluate(expression)) return;
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error("Timeout waiting for " + expression);
}
try {
  await call("session.new", {
    capabilities: { alwaysMatch: { acceptInsecureCerts: true } },
  });
  context = (await call("browsingContext.create", { type: "tab" })).context;
  await call("browsingContext.setViewport", {
    context,
    viewport: { width: 1280, height: 900 },
    devicePixelRatio: 1,
  });
  await call("browsingContext.navigate", {
    context,
    url: origin + "/cadastro",
    wait: "complete",
  });
  await waitFor('!!document.querySelector("#companyName")');
  const email = `browser-${Date.now()}@example.invalid`;
  if (process.env.FIXFLOW_E2E_EMAIL_FILE)
    await fs.writeFile(process.env.FIXFLOW_E2E_EMAIL_FILE, email, {
      mode: 0o600,
    });
  const fields = {
    companyName: "Oficina de teste do navegador",
    fullName: "Owner de teste",
    email,
    password: "Owner-browser-123!",
  };
  await evaluate(
    `(()=>{const fields=${JSON.stringify(fields)};for(const [id,value] of Object.entries(fields)){const input=document.getElementById(id);input.value=value;input.dispatchEvent(new Event('input',{bubbles:true}));}document.querySelector('form').requestSubmit();return true;})()`,
  );
  await waitFor(
    'location.pathname==="/entrar" && !!document.querySelector(".notice")',
  );
  await evaluate(
    `(()=>{const input=document.getElementById('password');input.value='Owner-browser-123!';input.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('form').requestSubmit();return true;})()`,
  );
  await waitFor(
    'location.pathname==="/inicio" && !!document.querySelector("app-workspace")',
  );
  await evaluate("document.querySelector('app-workspace button').click();true");
  await waitFor(
    'document.querySelector("[role=status]")?.textContent.includes("confirmado")',
  );
  if (
    await evaluate(
      "document.cookie.includes('fixflow_refresh') || localStorage.length > 0 || sessionStorage.length > 0",
    )
  )
    throw new Error("Secret persisted or refresh exposed");
  const shot = await call("browsingContext.captureScreenshot", { context });
  if (process.env.FIXFLOW_E2E_ARTIFACTS)
    await fs.writeFile(
      process.env.FIXFLOW_E2E_ARTIFACTS + "/workspace.png",
      Buffer.from(shot.data, "base64"),
    );
  await call("browsingContext.reload", { context, wait: "complete" });
  await waitFor(
    'location.pathname==="/inicio" && !!document.querySelector("app-workspace")',
  );
  await evaluate("document.querySelector('app-workspace button').click();true");
  await waitFor(
    'document.querySelector("[role=status]")?.textContent.includes("confirmado")',
  );
  await call("browsingContext.navigate", {
    context,
    url: origin + "/entrar",
    wait: "complete",
  });
  await waitFor('!!document.querySelector("#email")');
  await call("browsingContext.setViewport", {
    context,
    viewport: { width: 375, height: 812 },
    devicePixelRatio: 1,
  });
  if (await evaluate("document.documentElement.scrollWidth > innerWidth"))
    throw new Error("Mobile layout overflows");
  const mobile = await call("browsingContext.captureScreenshot", { context });
  if (process.env.FIXFLOW_E2E_ARTIFACTS)
    await fs.writeFile(
      process.env.FIXFLOW_E2E_ARTIFACTS + "/login-mobile.png",
      Buffer.from(mobile.data, "base64"),
    );
  console.log(
    "PASS: browser signup → login → authorized tenant; reload renews cookie session; secrets absent from JS storage; mobile 375px has no horizontal overflow.",
  );
} finally {
  try {
    await call("session.end");
  } catch {}
  ws.close();
}
