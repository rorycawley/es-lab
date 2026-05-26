# Development Workflow

This document is the authoritative development loop for the `04a-legal-facts`
project. It incorporates trunk-based development, behavior preservation, test
isolation, and LLM pairing, tailored for a highly modular Clojure system backed
by a Postgres event store.

This is the blueprint for **architecting for the unknown** - separating the
*what* (the stable behavior preserved by tests) from the *how* (the fluid
implementation details that will change).

See [`09-testing-strategy.md`](09-testing-strategy.md) for the test layers,
git gates, and CI configuration that this workflow depends on.


## The TDD Loop

Every unit of work turns the same three-step crank (Kent Beck, *Test-Driven
Development: By Example*):

| Step | Instruction | What it means in practice |
|------|-------------|--------------------------|
| **1. Red** | Write a little test that doesn't work, and perhaps doesn't even compile at first. | Small and focused. Targets the public boundary. Must fail before any implementation exists. |
| **2. Green** | Make the test work quickly, committing whatever sins necessary in the process. | Satisfy the behavior as fast as possible. Getting to green dominates everything else. This is not about accepting sin - it is about being sinful. Write sinful code. This shift in aesthetics is hard for experienced engineers; the instinct to write clean code first must be actively suppressed. |
| **3. Refactor** | Eliminate all of the duplication created in merely getting the test to work. | This is when you produce clean code - when you simplify. Remove duplication [Beck]. Sanitize code smells [Fowler]. Apply patterns [Kerievsky]. All three are the same act: simplification. The behavior test stays green throughout. |

The four phases below map directly onto this loop: Phase 1 ends at Red,
Phase 2 is Green, Phase 3 is Refactor, Phase 4 integrates the result.
The loop repeats for every acceptance criterion.

On Green:

> "The different phases have different purposes. They call for different styles
> of solution, different aesthetic viewpoints. The first three phases need to go
> by quickly, so we get to a known state with the new functionality. We can
> commit any number of sins to get there, because speed trumps design, just for
> that brief moment."
> - Kent Beck, *TDD by Example*

On Refactor - the cycle is not optional:

> "Now I'm worried. I've given you a license to abandon all the principles of
> good design. Off you go to your teams - 'Kent says all that design stuff
> doesn't matter.' Halt. The cycle is not complete. A four-legged Aeron chair
> falls over. The first four steps of the cycle won't work without the fifth.
> Good design at good times. Make it run, make it right."
> - Kent Beck, *TDD by Example*


## Phase 1 - Discovery and API Design (The Contract)

Before touching the implementation, define the boundary and design the
contract. The goal here is not testing - it is **ergonomic API design and
behavior preservation**. Tests must target the public boundary so that the
implementation can change freely without breaking the test.

1. **Identify the driver.** Start with the business pressure. What exact
   system behavior must be preserved, regardless of how the internals change
   in three years? Translate this into a clear *Given / When / Then* format
   with a named acceptance criterion ID (e.g. `AC-AP-007-001`).

2. **Target the component interface.** Pinpoint the exact public boundary
   (the module's port or HTTP endpoint) that will expose this behavior.

3. **Prompt the LLM as the first consumer.** Ask the LLM to write a test that
   calls this public API.
   > *Prompt strategy:* "We need to lock in this specific behavior:
   > [Given/When/Then]. Write a developer test targeting the public boundary of
   > this module. The goal is to preserve this exact behavior. Ensure the test
   > creates its own fresh state and does not rely on global variables or
   > shared fixtures."

4. **Evaluate the ergonomics.** Review the generated test. Is the module easy
   to call? Is the API intuitive? If it requires massive setup or leaks Postgres
   or event store implementation details, the design is wrong. Redesign the
   test with the LLM until the contract is elegant.

5. **Watch it fail (Red).** Run the isolated test locally. It must fail.
   The safety net is now armed.


## Phase 2 - The Implementation (MMMSS)

Move in **Many More Much Smaller Steps** to satisfy the contract. Speed and
functionality are the only goals here.

1. **Write the duct-tape (Green).** Prompt the LLM for the fastest, simplest
   Clojure code to make the test pass. Do not worry about clean namespaces,
   pure functions, or optimal queries yet. Just get to green.

2. **Build the scaffold (optional).** If the internal algorithm is highly
   complex, use the LLM to write tiny, granular tests targeting private
   functions just to work out the logic.

3. **Tear down the scaffolding.** Once the main behavioral test is green,
   **delete all granular scaffold tests.** Keeping them turns them into an
   obstacle to future change.


## Phase 3 - The Preservation Refactor

With behavior securely locked in and the test passing, mold the duct-tape code
into an enduring architecture.

1. **Simplify the internals.** Use the LLM to clean up the implementation.
   Extract pure functions, decouple logic from infrastructure, and optimise
   data structures. This is the moment to enforce the architecture principles
   (pure Deciders, ports and adaptors, no I/O in domain logic).

2. **The golden rule.** **Do not write new tests during this phase.**

3. **Rely on the shield.** As you restructure, keep running the behavioral
   test. If it stays green, the behavior is preserved. If it breaks, the test
   is coupled to the implementation - fix the test to target the boundary again,
   not the internals.


## Phase 4 - Continuous Integration (The Trunk)

A clean module and a preserved behavior are ready to integrate.

1. **Run the full local suite.** Execute the entire test suite. Because every
   test creates its own state and tears it down, they run fully in parallel
   and finish in seconds.

2. **Push to trunk.** Commit directly to `main`. No feature branches. No
   asynchronous pull requests. Every commit leaves the system in a releasable
   state.

3. **Automated verification.** Pushing to GitHub triggers the GitHub Actions
   pipeline, which runs the full suite to ensure no preserved behavior was
   violated.

4. **Deploy.** If the suite is green, GitHub Actions automatically deploys the
   updated artifact to the target environment.

5. **The 10-minute rule.** If the build goes red, a preserved behavior has been
   broken. Stop all new development, swarm the issue, and push a fix or revert
   within 10 minutes to keep the trunk pristine.
