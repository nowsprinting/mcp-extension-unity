---
name: code-writing-guide
description: Used when writing or modifying code. Provides coding guidelines for this project.
---

Guide for writing code in this project.

## "Why Not" Comments

Add a comment whenever a non-obvious implementation choice was made — especially when a natural
or standard approach was tried and rejected. The goal is to prevent future readers (human or AI)
from re-discovering the same dead end.

**Triggers that require a "why not" comment:**

- A standard API or language feature is avoided because it misbehaves in a specific environment
  (e.g., `withTimeout` deadlocks on the IntelliJ platform test JVM EDT → use `java.util.Timer`)
- A less-efficient or more verbose pattern is chosen over a simpler one for correctness reasons
- A seemingly redundant guard, indirection, or workaround exists due to a framework constraint

**Format:** Explain what was tried, why it failed, and why the chosen approach avoids the problem.

```kotlin
// WHY java.util.Timer instead of withTimeout:
// withTimeout relies on DefaultDelay, whose cancellation is dispatched back to the coroutine's
// event loop. When runBlocking is called from the EDT, the event loop runs ON the EDT — which
// runBlocking blocks — so the callback can never be processed and the timeout never fires.
// java.util.Timer runs on its own daemon thread and calls continuation.resume() directly,
// bypassing the scheduler entirely.
```