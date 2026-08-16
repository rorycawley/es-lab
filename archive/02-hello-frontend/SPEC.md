# Spec: 02-hello-frontend

## Story 1 — Zero-dependency setup

> As a developer, I want to run the full stack locally without installing any language runtimes or build tools on my machine, so that I can get it running regardless of my existing environment.

**Acceptance criteria:**
- Given a machine with only Docker installed, when I start the stack, then both the frontend and backend run successfully without any additional installation steps

**Verified by:** `docker compose up --wait` — the stack starts cleanly with only Docker installed. `bb test:acceptance` also exercises this as a side-effect (the stack-fixture runs `docker compose up` before every acceptance test run), but running the automated verifier requires the project toolchain (Babashka, mise).

---

## Story 2 — Health status visibility

> As a user, I want to see whether the backend is healthy when I open the application, so that I can immediately tell if the system is working.

**Acceptance criteria:**
- Given the stack is running and the backend is healthy, when I open the application, then I see "Project is confirmed to be healthy"
- Given the stack is running and the backend is unreachable, when I open the application, then I see "Project not healthy"
- Given the application is loaded, when I enter a different endpoint path in the input and click Check, then the health check fires against that path
- Given the application is loaded, when I click Check without changing the path, then a new health check still fires against the same path

**Verified by:** `bb test:frontend` — Jest unit tests with mocked HTTP responses cover all three UI states and the re-check behaviour

---

## Story 3 — Health status E2E visibility

> As a developer, I want a browser-level test that confirms the health message is rendered in a real browser, so that I know the full stack — backend, nginx proxy, and Angular — is wired together correctly.

**Acceptance criteria:**
- Given the full Docker stack is running, when Playwright navigates to `http://localhost:4200`, then it finds the text "Project is confirmed to be healthy" visible in the browser within 10 seconds

**Verified by:** `bb test:e2e` — Playwright drives a real Chromium browser against the live Docker stack

---

## Story 4 — Clean shutdown

> As a developer, I want to stop the stack cleanly, so that no orphaned processes are left consuming resources.

**Acceptance criteria:**
- Given the stack is running, when I stop it, then all processes terminate and no containers remain running

**Verified by:** `bb test:acceptance` and `bb test:e2e` — both call `docker compose down` in a finally block after every run
