# Architecture: 02-hello-frontend

## Runtime

```
                    ┌──────────────── Docker Compose ──────────────────────┐
                    │                                                        │
  localhost:4200 ───┼──▶  frontend  (nginx:alpine)                :80       │
                    │     │                                                  │
                    │     │  /api/*        proxy_pass → strips /api/ prefix ─┼──▶  backend  (eclipse-temurin:21-jre)  :8080
                    │     │  /*.js,*.css   static, Cache-Control: immutable  │     │
                    │     │  /*            try_files $uri /index.html        │     │  GET /health        → {status, version}
                    │     │                                                  │     │  GET /openapi.json  → OpenAPI 3.0.3 spec
                    │     └── depends_on: backend condition: service_healthy │     │  GET /swagger-ui    → API browser
                    │                                                        │     │
  localhost:8080 ───┼────────────────────────────────────────────────────────┼──▶  │  healthcheck: wget http://localhost:8080/health
  (direct)          │                                                        │     │  (frontend container waits until this passes)
                    └────────────────────────────────────────────────────────┘
```

The nginx proxy strips the `/api/` prefix before forwarding: a browser request to
`/api/health` reaches the backend as `GET /health`. The `try_files` fallback in the
`/*` location makes Angular client-side routing work — any unknown path returns
`index.html` and Angular's router takes over.

The frontend healthcheck uses `http://127.0.0.1/` (not `localhost`) because
`nginx:alpine` binds only to IPv4 and `localhost` resolves to `::1` on some alpine images.

---

## Image builds

```
frontend                                    backend
────────────────────────────────────────    ────────────────────────────────────────
Stage 1: node:22-alpine                     Stage 1: clojure:temurin-21-tools-deps-alpine
  COPY package.json package-lock.json         COPY deps.edn build.clj
  RUN npm ci                    ← cached      RUN clojure -P              ← cached
  COPY . .       (src after deps)             COPY src resources
  RUN ng build --configuration=production     RUN clojure -T:build uber
  → dist/frontend/browser/                   → target/app.jar

Stage 2: nginx:alpine                       Stage 2: eclipse-temurin:21-jre-alpine
  COPY dist/frontend/browser/ /usr/share/     RUN addgroup/adduser app    ← non-root
       nginx/html/                            COPY target/app.jar app.jar
  COPY nginx/default.conf                     ENTRYPOINT java -jar app.jar
```

Both use layer-cache ordering: dependency files are copied and resolved before source
is copied, so `npm ci` and `clojure -P` only re-run when the dependency manifests change.
`backend/.dockerignore` excludes `target/` and `.cpcache/`; `frontend/.dockerignore`
excludes `node_modules/`, `.angular/`, `dist/`, `e2e/`, and test artefacts.

---

## Angular internals

```
main.ts
  bootstrapApplication(AppComponent, {
    providers: [
      provideZonelessChangeDetection(),   ← no zone.js; re-renders on signal change only
      provideHttpClient(),
    ]
  })

AppComponent
  endpointModel: signal({ path })         ← source of truth for the input value
  endpointForm:  form(endpointModel)      ← Signal Forms FieldTree; [formField] binds to <input>

  checkTrigger:  signal<[path, n]>        ← tuple so same-path re-check always emits
  healthy:       toSignal(
                   toObservable(checkTrigger).pipe(
                     switchMap([path] → http.get(path).pipe(
                       map(status === 'ok'),
                       catchError(() => false),
                       startWith(null),      ← resets to "Checking…" on every re-check
                     ))
                   ), { initialValue: null }
                 )
```

`angular.json` sets `polyfills: []` (removes zone.js) and uses the
`@angular-devkit/build-angular:application` esbuild builder, which detects
`tailwind.config.js` automatically — no PostCSS config is required.

---

## Test layers

```
Layer              Tool                       Docker   What it proves
─────────────────  ─────────────────────────  ───────  ──────────────────────────────────────
backend unit       kaocha (clojure -M:test)   no       health-handler returns correct data
backend integ.     kaocha + live Jetty         no       HTTP routes, JSON, OpenAPI, Swagger UI
frontend unit      Jest + jsdom                no       all UI states and re-check behaviour
HTTP acceptance    Babashka http-client        yes      nginx proxy, port mapping, wiring
E2E browser        Playwright (Chromium)       yes      full render in a real browser
```

`bb test` runs all five sequentially. `test:acceptance` manages Docker inside a
`use-fixtures :once` fixture; `test:e2e` manages Docker in `bb.edn` (because Playwright
is an external process). Docker starts twice when running `bb test` — this is intentional
so every task is independently runnable.

`jest.config.ts` sets `testMatch: ['<rootDir>/src/**/*.spec.ts']` to prevent Jest from
picking up `e2e/app.spec.ts`, which uses Playwright imports incompatible with jsdom.
`setup-jest.ts` calls `setupZonelessTestEnv()` from `jest-preset-angular`.
`tsconfig.spec.json` overrides to `module: CommonJS` for Jest compatibility.
