# Alpha AI for Android

Alpha AI is a native, dark-first Android agent shell for BYOK coding, research, and artifact workflows. A task is planned into a checklist, the agent uses a provider router and an execution profile, and output files remain app-private until the user explicitly shares or exports them.

> **Build status:** This repository has not yet been built or tested in this sandbox. The sandbox has no JDK or Android SDK, so neither `./gradlew assembleDebug` nor `./gradlew test` has been run. Treat this as active implementation work, not a verified release. The Gradle wrapper is included; use Android Studio or a local JDK 17 + Android SDK to validate it.

## Requirements

- Android Studio (current stable) or JDK 17 with Android SDK Platform 35 and Build Tools installed.
- Android 8.0 / API 26 or later.
- At least one provider key; Groq is the recommended quick start.
- For the default Remote sandbox profile: an E2B key and network access.

## Get the code onto your PC

Use whichever transfer option the Arena session UI offers:

1. **ZIP/export:** download the workspace/repository ZIP, extract it, and verify it contains `gradlew`, `settings.gradle.kts`, `app/`, and the recent changes. Open the extracted repository root in Android Studio.
2. **GitHub:** if you use Arena's GitHub connection, connect the intended GitHub account/repository and push the active branch `arena/89b11233-alpha-ai`; then clone/fetch that branch on your PC. The branch should be pushed before running `git clone` if it is not already present on GitHub. The repository remote configured for this checkout is `https://github.com/lk0024709-ux/Alpha-ai-.git`.

For a local clone after the branch is available remotely:

```sh
git clone https://github.com/lk0024709-ux/Alpha-ai-.git
cd Alpha-ai-
git fetch origin arena/89b11233-alpha-ai
git switch --track origin/arena/89b11233-alpha-ai
```

If the repository is private, authenticate with GitHub through your normal Git client/credential manager; never paste tokens or passwords into the app, task text, or chat. If Arena offers no connector/export in your session, download the workspace ZIP instead.

## Build

Open the repository root in Android Studio and let Gradle sync. Under **Settings → Build, Execution, Deployment → Build Tools → Gradle**, select a JDK 17 Gradle JDK. Then build the default `lightDebug` variant or run:

```sh
./gradlew assembleDebug
./gradlew test
```

The `light` flavor is the default. It excludes the Chaquopy plugin and Python runtime from routine builds. For the opt-in Python flavor, use:

```sh
./gradlew assembleFullPythonDebug
```

A task whose name includes `fullPython` activates Chaquopy, Python 3.11, and numpy/pandas/matplotlib dependencies. A local explicit activation flag is also available for custom fullPython build tasks:

```sh
./gradlew -Palphaai.enableFullPython=true assembleFullPythonDebug
```

The fullPython APK is substantially larger (expect roughly +80 MB; exact size depends on ABI and package resolution). The fullPython build has not been validated in this checkout.

## Providers and keys

Paste keys in **Settings**. Keys are encrypted using Android Keystore-backed `EncryptedSharedPreferences`; they are not saved in Room, DataStore, message history, or logs. Settings applies `FLAG_SECURE`. Clear a key in Settings to remove it.

- Groq: [console.groq.com/keys](https://console.groq.com/keys) — default `llama-3.3-70b-versatile`; cheap helper `llama-3.1-8b-instant`.
- Gemini: [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey) — `gemini-2.0-flash` and `gemini-1.5-pro`; the REST client supports SSE and inline image/PDF parts.
- Hugging Face: [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens) — fallback chat model `Qwen/Qwen2.5-Coder-32B-Instruct`; embedding endpoint `BAAI/bge-small-en-v1.5`.
- E2B: [e2b.dev](https://e2b.dev/) — used by the Remote sandbox. The app sends the key in an auth header and creates an ephemeral sandbox. E2B API plans and REST endpoints can change; check your account's current API documentation if sandbox creation fails.
- Serper: optional key slot is present for future search integration. DuckDuckGo HTML is the default search implementation; the current implementation does not yet call Serper.

Provider credentials are attached by an OkHttp interceptor from the secure store at request time. Do not place keys in source, task text, or bug reports. `SafeLog` is DEBUG-gated and truncates output; release builds emit no app logs.

## How routing works

- `AUTO`: Groq for ordinary prompts; Gemini 1.5 Pro for prompts estimated above 60k tokens or tasks containing an attached image/PDF.
- 429 and provider errors move to the fallback chain Groq → Gemini → Hugging Face. Providers with two consecutive failures are skipped until a success resets the counter.
- Requests, latency, token counts (or estimates where a provider omits usage), result status, and estimated costs are recorded in Room. Cost figures are approximate model-price estimates, not provider billing records.
- Per-task cost cap defaults to $0.50 (user configurable, never above the $0.50 hard limit); the agent also enforces 25 tool steps and a ten-minute wall-clock ceiling. Repeated identical tool calls stop the run.

## Execution profiles

- **Remote (default):** E2B ephemeral VM intended for shell, Python, Node, and Playwright browser automation. Files should be explicitly listed in the `finish` tool call to import them into the app's private artifact area. The public E2B REST surface is evolving; endpoint compatibility needs device-side validation.
- **Light (on-device opt-in):** scoped filesystem under app-private `files/artifacts/`; shell/browser are unavailable. JavaScript uses the optional QuickJS Android dependency. Python is available only in `fullPython`. Network requests from Light are disabled unless the hostname is in the Settings allowlist. SSRF checks reject local, private, link-local, and reserved IPs; redirects are blocked for direct HTTP requests.

No root, broad storage permission, or `MANAGE_EXTERNAL_STORAGE` permission is requested. Artifacts stay private until Share or the system Create Document picker is used.

## Test the E2B Remote sandbox locally

This smoke test requires a built/installed app, a Groq key, an E2B account/key, and a working internet connection. Settings includes an E2B “Test” action that creates a temporary sandbox, runs a short echo command, and deletes the sandbox; a real task remains the end-to-end check.

1. In **Settings**, save and test the Groq key. Save the E2B key, then press E2B **Test** and confirm it reports `Connected`.
2. On Home, select **Remote sandbox** and **Groq** or **Auto**.
3. Submit: “Use Python in the remote sandbox to print `hello from E2B`, write the same text to `hello.txt`, verify the file by reading it back, then finish and include `hello.txt` as an artifact.”
4. In the run screen, confirm that the plan is created and the tool card(s) return the expected text. Open **Artifacts** and verify that `hello.txt` was imported; export it through the document picker if desired.
5. If sandbox creation or execution fails, preserve the HTTP status/error shown in the run and compare the create/exec/delete paths and auth-header name with the current E2B REST documentation. Do not include the E2B key in a bug report.

The remote E2B REST integration has not been validated against a live account from this environment. A failed smoke test does not imply the Android build failed; distinguish Gradle/build errors from E2B HTTP/API compatibility errors.

## Cost expectations

Provider pricing changes, model routing may fall back, and actual costs depend on prompt/output length. The in-app meter uses rough per-million-token estimates and should not be used for billing or budgeting guarantees. A small Groq task is typically far below a cent; E2B may have separate sandbox charges. Check each provider's current pricing before running large jobs.

## Three-minute demo

1. Install the `lightDebug` build and open **Settings**.
2. Paste a Groq key, save, and press **Test**; a successful test reports `Connected`.
3. In Home choose **Remote sandbox**, add an E2B key, and submit the “Scrape a page” task from the example cards.
4. Watch the plan, tool cards, and token/cost meter in the run screen. Finish with a Markdown report artifact.
5. Open **Artifacts**, preview the report, then Share or export it through the Android document picker.
6. Optional: add a Gemini key, start a fresh run, attach an image/PDF, and ask for a visual report. The router selects Gemini for attachments.

Prompts for live demos are in [`demo/TASKS.md`](demo/TASKS.md).

## Security and data

- `android:usesCleartextTraffic="false"`; API traffic uses HTTPS.
- Encrypted provider keys are excluded from backup and device transfer; backup is disabled for the app.
- Session text, plans, tool-call arguments/results, artifacts, and estimated usage are local app data. Do not use the app for secrets that should not be sent to a selected model or sandbox.
- `http_request` blocks redirects and validates DNS addresses before sending. Remote sandbox code has the network permissions of the E2B environment; do not treat that VM as a substitute for reviewing generated code.
- No analytics, accounts, cloud sync, or in-app billing are included.

## Current implementation notes

The app contains the Room/DataStore/security foundation, provider clients/router, sandbox abstractions, agent loop, foreground service/resume worker, and the primary Compose screens. Some advanced presentation details remain deliberately conservative: HTML previews render inert source text (no JavaScript/WebView execution), some artifact formats use a text preview, and provider/E2B behavior still requires emulator/device integration testing. The build and automated test suite are **unverified** until run with Android Studio/JDK/SDK.
