---
name: implementation-planning-guide
description: Used when planning implementation in Plan mode. Include the test design and implementation steps in the plan file.
---

Guide for plan mode. This skill provides the test design guidelines and the development workflow steps to include in plan files.

## Test Design Guidelines

In plan mode, design your tests according to the following guidelines and include test cases in the plan file:

### 1. Analyze specifications

Read the current plan and identify testable specifications.
If the specifications are unclear, use the AskUserQuestionTool to request clarification before proceeding.
If the test target has low testability, modify the design.

### 2. Identify Test Targets

Choose public classes and methods under test in order of the **least integrated level** (unit tests first).

### 3. Select Testing Techniques

For each test target, select appropriate techniques:

- **Equivalence partitioning** — group inputs into valid/invalid classes; one representative per class
- **Boundary value analysis** — test at the edges of each equivalence class.
- **State transition testing** — if the target has a finite-state-machine (FSM); one test case covers only 0-switch coverage
- **Decision table testing** — if multiple conditions combine to produce different outcomes

### 4. Create Test Cases

For each technique, derive coverage-aware test cases:

- Use the naming convention: `MethodName_Condition_ExpectedResult`
- Do NOT create sequential IDs in test case names
- Describe the verification content clearly
  - Verify one condition per test
  - Test concerns separately
- If a test case requires a test double, state it in the Description column: e.g., `(uses spy: <TargetDependency>)`. Choose the type based on xUnit Test Patterns (xUTP) definitions:
  - **Stub** — returns canned responses to isolate the SUT from a dependency
  - **Spy** — records interactions (calls, arguments) for later verification
  - **Fake** — a simplified but working implementation of a dependency
- Drop test cases that cannot be verified by test code
  - Instead, list them as E2E test items in the `### E2E Tests` section of the plan file

### 5. Test Case Format

Append test cases to the plan file using this format:

```markdown
### Test Cases of {dotnet|kotlin} tests

#### <ClassName>

| Test Method                 | Description                                |
|-----------------------------|--------------------------------------------|
| `Method_Condition_Expected` | Brief description of what is verified      |
| `Method_Condition_Expected` | Brief description (uses stub: IDependency) |

### E2E Tests

| # | Item                          | Verification Method               |
|---|-------------------------------|-----------------------------------|
| 1 | Brief description of the item | How to verify (e.g., visual check)|
```

## Development Workflow

Include the following implementation steps in the plan file:

### Step 1: Skeleton (Compilable)

1. Create only the types and public method signatures for the product code that can be compiled. It's okay even if it does not work

### Step 2: Test First

1. Implement test code based on the test cases in the plan file
2. Run the added tests, and confirm that they **fail**
3. Commit test code to git (skeleton is not committed yet — do not include it) — if test code is modified in Step 3 or later, the integrity of Test First is compromised; commit here without fail so the diff remains verifiable

### Step 3: Implementation

1. Implement the product code
2. If the test cases require test doubles, create them as separate files. Do NOT define test doubles in the test class file.
3. Run the tests, and confirm that they all **pass**
4. Commit product code to git (includes skeleton from Step 1, and any unavoidable test code changes)

### Step 4: Refactoring

1. Resolve diagnostics at warning or higher for each modified file (`mcp__jetbrains__open_file_in_editor` → `mcp__ide__getDiagnostics` → fix, one file at a time; use `mcp__ide__getDiagnostics` because the Unity editor compiler does not reflect `.editorconfig` severity settings)
2. Run the tests, and confirm that they all **pass**
3. Run the Claude Code built-in `/simplify` skill (`Skill({skill: "simplify"})` — not a plugin skill) to apply quality improvements to the modified code
4. Run the tests, and confirm that they all **pass**
5. Commit all remaining changes to git

### Step 5: E2E Tests

1. Add new E2E test cases directly to `docs/e2e-tests.md` under the relevant tool section.
   - Follow the existing format and numbering in that file.
2. **Exception**: If a test case requires a disruptive user operation that is impractical to run every regression cycle (e.g., stopping Unity Editor, disconnecting the network), create a separate file at `docs/plans/{plan-file-name}-e2e-tests.md` instead.
   - `{plan-file-name}` is the plan file name copied to `docs/plans/` (e.g., `2026-01-18-plan-name`).
