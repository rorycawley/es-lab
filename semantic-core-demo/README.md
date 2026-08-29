# Semantic Core Demo

One generic pure semantic engine runs three unrelated domains using data-only bundles:

- ticket sales
- land-title transfers
- company incorporations

The point of the project is that the Clojure engine knows nothing about those
domains. Each domain supplies its rules, decisions, state machines, state
updates, and workflow reactions as EDN data.

## Run it

Production code only depends on Clojure itself. Kaocha is included under the
test alias, and the development tools are pinned by `mise.toml`.

```bash
bb demo
bb test
bb lint
bb fmt:check
bb all
```

`bb demo` prints one successful end-to-end example per bundle. `bb test` runs
the unit, integration, rejection, transition, and workflow tests through
Kaocha. `bb lint` runs clj-kondo, `bb fmt:check` runs cljfmt, and `bb all`
installs the pinned tools before running all checks, tests, and the demo. Use
`bb fmt:fix` to apply formatting. The underlying Clojure aliases are
`clojure -M:demo` and `clojure -M:test`.

## How execution fits together

```text
past events --hydrate/evolve--> aggregate state
command + aggregate state --decide--> accepted events or a rejection
accepted events --evolve--> new aggregate state

incoming message + process state --react--> workflow events and commands
workflow event --evolve-process--> new process state
```

In algebraic form:

```text
decide         : Operators × Bundle × Command × State -> DecisionResult
evolve         : Operators × Bundle × State × Event -> State
react          : Operators × Bundle × Message × ProcessState -> ReactionResult
evolve-process : Operators × Bundle × ProcessState × Event -> ProcessState
```

The engine functions return new values; they neither mutate state nor perform
I/O. No domain name appears in `engine.clj`.

## Repository map

- `src/semantic_core/engine.clj` is the generic interpreter. It evaluates
  expressions, checks decision rules, renders emitted messages, evolves
  aggregate FSMs, and runs/evolves workflows.
- `src/semantic_core/operators.clj` is the allow-list of functions that EDN
  expressions may invoke. Keeping functions outside the bundles means bundles
  remain inert data read by `clojure.edn`.
- `src/semantic_core/demo.clj` loads classpath bundles and wires together three
  illustrative end-to-end cases. It is the only production namespace that
  reads resources or prints output.
- `resources/bundles/*.edn` contain all domain-specific behavior.
- `test/semantic_core/engine_test.clj` verifies the expression and update
  algebras plus all three bundles' positive, negative, FSM, and workflow paths.
- `bb.edn` provides the same demo, lint, test, and aggregate task interface used
  by the neighboring labs.
- `deps.edn` pins Clojure, adds `src` and `resources` to the normal classpath,
  runs the demo through `:demo`, and adds the test path and Kaocha through
  `:test`.
- `tests.edn` is Kaocha's test-discovery configuration.
- `mise.toml` pins Java, Clojure CLI, Babashka, clj-kondo, and cljfmt versions.

## The expression language

Bundle expressions are deliberately small vectors interpreted by `evaluate`:

```clojure
[:value x]                  ; literal x
[:state [:path :to :value]] ; read from current state
[:input [:data :field]]     ; read from the command/event/message
[:op :core/= expr-a expr-b] ; evaluate arguments, then call an allowed operator
```

The available operators are `=`, `not=`, `and`, `or`, `not`, `<`, `<=`, `>`,
`>=`, and `contains?`, all under the `:core` namespace. `render` recursively
resolves embedded expressions inside maps, vectors, and sets; this is how an
event or command template becomes a concrete message.

State updates use one of three data instructions:

```clojure
[:set  [:path] expression]
[:conj [:path] expression]
[:disj [:path] expression]
```

## Bundle anatomy

Every bundle has the same shape:

- `:rules` names boolean assertions and their rejection reason.
- `:decisions` matches a command type, evaluates its ordered rule references,
  and renders events only when all rules pass. The first failing rule supplies
  the rejection.
- `:fsms` declares event-driven state transitions.
- `:state-model` provides initial aggregate state, connects state paths to
  FSMs, and declares additional event-driven updates.
- `:workflow` separately matches incoming event messages and emits process
  events plus commands. Its `:on-events` section evolves workflow-local state.
- Version and operator/interpreter identifiers are descriptive metadata in this
  demo; the engine does not perform schema or version validation.

The ticket bundle requires a held seat owned by the customer. The land bundle
requires an active title whose proprietor is the transferor. The corporate
bundle requires a submitted application, confirmed payment, and a different
second examiner. Their vocabulary differs, but all take the same engine path.

Unknown aggregate or workflow events are no-ops. A command with no decision,
an unknown expression operator, or an unknown update operation raises an
explicit exception. This is a small semantic-core demonstration, not an entity
store, transport layer, persistence system, or production bundle validator.
