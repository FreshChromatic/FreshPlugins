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

### Readable formatting

- Do not over-compress code.
- Do not place multiple statements, branches, exception handlers, or method definitions on one line.
- Avoid nested ternaries, long call chains, and large lambdas for business logic.
- Extract complex boolean conditions into clearly named variables or methods.
- Format DTO construction, error responses, command results, and transaction flows across readable lines.
- Use request or value objects when a method requires too many parameters.
- Do not shorten names or collapse structure merely to reduce line count.

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
