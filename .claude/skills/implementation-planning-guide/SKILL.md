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

Create only the types and public method signatures for the product code that can be compiled. It's okay even if it does not work.

### Step 2: Test First

1. Implement test code based on the test cases in the plan file.
2. Run the added tests, and confirm that they **fail**.
3. Commit to git.

### Step 3: Implementation

1. Implement the product code.
2. If the test cases require test doubles, create them as separate files. Do NOT define test doubles in the test class file.
3. Resolve diagnostics at the `error` severity level, using the `mcp__jetbrains__get_file_problems` tools.
4. Run the tests, and confirm that they all **pass**.
5. Commit to git.

### Step 4: Refactoring

1. Refactor with DRY, KISS, and SOLID principles in mind, re-run tests to pass.
2. Resolve diagnostics at the `suggestion` or higher severity level, re-run tests to pass.
3. Reformat the modified files, using `mcp__jetbrains__reformat_file` tool.
4. Commit to git.

### Step 5: E2E Tests

1. Create the E2E test cases file at the path: `docs/plans/{plan-file-name}-e2e-tests.md`
   - `{plan-file-name}` is the plan file name copied to `docs/plans/` in the guidelines (e.g., `2026-01-18-plan-name`).
2. Refer to `docs/e2e-tests.md` for the content and format of the E2E test cases.
