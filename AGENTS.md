# Development Guidelines

## Code Style

### Minimal comments

- Do not add comments unless they are necessary.
- Prefer clear naming, strong types, encapsulation, abstraction, and module boundaries.
- Do not use comments to explain code that can be made clear by extracting a method, value object, strategy, or service.
- Do not add comments that merely repeat what the code does.
- Do not keep commented-out implementations. Git preserves history.
- TODO comments must reference a concrete work item and explain the blocker.

Comments are appropriate only for:

- Complex algorithms that remain non-obvious after proper decomposition.
- Non-obvious Minecraft compatibility behavior.
- Security boundaries, protocol restrictions, or file-format invariants that could be broken by an apparently harmless simplification.
- Important reasoning that cannot be expressed through types or APIs.

When a comment is necessary, explain why the constraint exists. Do not narrate the implementation line by line.

### OOP and modular design

- Keep each class, component, and module focused on one responsibility.
- Put domain behavior in named domain objects or services, not in controllers, React components, or generic utility files.
- Prefer strongly typed DTOs, value objects, sealed hierarchies, and explicit interfaces over raw maps or unstructured payloads.
- Controllers should translate protocols and call application services. They should not contain compilation, asset, publication, or transaction rules.
- React components should render and compose UI. They should not own command, revision, conflict, deployment, or resource-pack business logic.
- Give mutable state one explicit owner. Do not propagate state through global variables or temporary DOM state.
- Prefer composition and small interfaces over deep inheritance.
- Introduce abstractions to solve concrete repetition or boundary problems, not speculative future requirements.

### Java layout and formatting

- Follow the self-contained rules and examples in this document for field grouping, column alignment, and call layout. Preserve this project's architecture, domain naming, and behavior when formatting code.
- Use conventional project formatting: compact where simple, expanded where complexity requires it. Avoid both compressed one-line logic and unnecessarily tall code.
- Use four-space indentation, four-space continuation indentation, and a preferred Java line width of 120 characters. Readable method declarations and indivisible expressions may exceed it; do not force a staircase solely to meet the target.
- Group fields by responsibility: constants, injected dependencies, related collections, and mutable session or UI state. Separate those groups with one blank line.
- Align field names and `=` within a related, uninterrupted group. Start a new alignment group after a blank line; do not align unrelated groups, local variables, or ordinary assignment statements.
- Keep constructor dependency assignments together, and separate them from collection initialization when that distinction improves readability. Use `this.field = parameter` when assigning constructor parameters to fields.
- Keep a simple declaration and its initializer on the same line when they fit. Do not put `=` on a separate line from a short initializer.
- Keep simple method calls, short lambdas, argument lists, and short call chains together when they fit. Do not require one argument, chained method, or nested call per line.
- Wrap expressions only when their length or logical structure warrants it. Prefer natural argument or expression boundaries, and avoid staircase formatting for ordinary expressions.
- Do not place multiple statements, branches, exception handlers, or method definitions on one line.
- Keep control-flow blocks and multi-statement lambdas expanded with braces. Short expression lambdas may stay inline.
- Avoid nested ternaries, difficult call chains, and large lambdas for business logic.
- Extract complex boolean conditions into clearly named variables or methods.
- Expand DTO construction, error responses, command results, and transaction flows when they are genuinely complex; do not expand short constructions solely because of their type or purpose.
- Use request or value objects when a method requires too many parameters.
- Do not shorten names or collapse structure merely to reduce line count.
- Do not introduce helper methods or temporary variables solely to satisfy an overly aggressive line-wrapping rule.
- Keep the start of a multiline initialization with its declaration, such as `var row = action(`. Keep natural outer call openings together, such as `rows.add(StatusRow.info(`, rather than putting each wrapper on its own line.
- For long declarative calls, group short related arguments on the same line and put substantial values or nested blocks on subsequent lines. Preserve the visual structure of configuration lists instead of blindly putting every argument on a separate line.
- Use the configurable Eclipse JDT profile in `tools/java-format.properties` through `tools/format-code.ps1`. Do not run Google Java Format/AOSP over these sources; it removes the intended alignment and imposes a different wrapping style.

Preferred for simple expressions:

```java
LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
rows.add(SettingsAddon.StatusRow.info(tr("phase"), () -> tr("phase." + session.phaseKey())));
```

Use multiple lines for substantial argument lists or actual blocks of logic, not for every syntactic layer of a simple call.

Preferred for related fields:

```java
private static final int NEAR_REFRESH_TICKS = 20;
private static final int FAR_REFRESH_TICKS  = 200;

private final Map<Long, LevelChunk> scanned;
private final Map<Long, Long>       lastScannedTick;
```

### Stable domain naming

- Do not use milestone, sprint, phase, temporary, or implementation-order names in production source filenames, exported types, components, services, routes, or CSS entrypoints.
- Name source artifacts for the domain responsibility they own, such as `DesignerApplication`, `ProjectHub`, or `CommandDispatcher`.
- Milestone labels belong in plans, release notes, test-report artifact names, and documentation only.
- Keep variable, method, and type names clear and concise. Avoid unnecessarily verbose names, but do not sacrifice meaning or readability merely to make names shorter.

### Decomposition

Reconsider the design when:

- A class handles routing, parsing, business rules, persistence, and serialization.
- A React component manages pages, drag-and-drop, resource packs, publishing, and multiple inspectors.
- A method contains several independent phases or needs several explanatory comments.
- A stylesheet depends on repeated token definitions or conflicting selectors.
- A test must construct many unrelated dependencies to verify one behavior.

Split responsibilities with explicit dependencies and independently testable modules. Do not move the same complexity into a generic `Utils` class, service locator, or global store.
