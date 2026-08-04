<div align="center">

<br>

<img src="https://cdn.modrinth.com/data/cached_images/ec9e3a4f43f54676a7eecf4945e1e60edc283f34_0.webp" alt="CR" width="700">

---

[![Documentation](https://img.shields.io/badge/Docs-RealisticPlantGrowth?style=for-the-badge&logo=gitbook&labelColor=gray&color=c6e2ff)](https://freshchromatic.github.io/docs/)
[![Github](https://img.shields.io/badge/Github-RealisticPlantGrowth?style=for-the-badge&logo=github&labelColor=gray&color=f5f5f5)](https://github.com/FreshChromatic/FreshPlugins)
[![Discord](https://img.shields.io/discord/1533083452071481374?style=for-the-badge&logo=discord)](https://discord.gg/XwpbGGGRk6)
</div>

<h2>
  <img src="https://cdn.modrinth.com/data/cached_images/1eed247362ef30bae4948fd957f3ac5620351aba.png" alt="" width="37" align="absmiddle">
  &nbsp;About
</h2>

ChunkRevive is a terrain lifecycle-management plugin for long-running servers. It helps staff identify terrain that has already been explored, mark exactly what should be processed, and regenerate or remove it.

Use it to keep resource worlds, exploration zones, and renewable structures fresh while preserving areas with player activity, protected land, and the parts of the world you intentionally want to keep.

ChunkRevive is also commonly used after world updates, making it easy to generate newly introduced terrain without manually locating and deleting region files.

<h2>
  <img src="https://cdn.modrinth.com/data/cached_images/1eed247362ef30bae4948fd957f3ac5620351aba.png" alt="" width="37" align="absmiddle">
  &nbsp;Features
</h2>

- Mark and unmark individual chunks, chunks you walk through, coordinate targets, or a chosen radius.
- Scan already-generated terrain directly from region files—ideal for full worlds and large resource worlds.
- Target biome areas using configurable biome matching, including full-world and radius-based biome scans.
- Not just chunk regeneration—also delete selected chunks or safely prune eligible complete Anvil regions.
- Track structures automatically, refresh them on a schedule, and expand protection to their full footprint.
- Let players check nearby tracked-structure protection progress with `/keep`, `/keepchunk`, or `/chunkkeep`.
- Respect configured world allow/deny rules and optionally integrate with claims managed by third-party land-protection plugins.

<h2>
  <img src="https://cdn.modrinth.com/data/cached_images/1eed247362ef30bae4948fd957f3ac5620351aba.png" alt="" width="37" align="absmiddle">
  &nbsp;How It Works
</h2>

1. **Discover** existing terrain with manual marking, radius/biome scans, full-world scans, or automatic structure detection.
2. **Review** marked chunks using in-game displays, status commands, and structure information.
3. **Revive** selected terrain with safe regeneration, or use the configured reset strategy to delete individual chunks or complete Anvil regions when it is safe to do so.
4. **Protect** what matters. World allow/deny rules, third-party land-protection plugin integration, structure tracking, confirmation flags, work-tile limits, and heap-aware throttling are applied throughout the workflow.

<h2>
  <img src="https://cdn.modrinth.com/data/cached_images/1eed247362ef30bae4948fd957f3ac5620351aba.png" alt="" width="37" align="absmiddle">
  &nbsp;Requirements
</h2>

ChunkRevive requires the following:
- 1.21.11+ (Paper、Folia)
- Java Version 21+
- FreshLib Plugin

<h2>
  <img src="https://cdn.modrinth.com/data/cached_images/1eed247362ef30bae4948fd957f3ac5620351aba.png" alt="" width="37" align="absmiddle">
  &nbsp;References
</h2>

- [Dominion](https://github.com/LunaDeerMC/Dominion) - Text User Interface (TUI)

<h2>
  <img src="https://cdn.modrinth.com/data/cached_images/1eed247362ef30bae4948fd957f3ac5620351aba.png" alt="" width="37" align="absmiddle">
  &nbsp;Afterword
</h2>

This project was fully led and edited by tcib_cat, who was responsible for its overall planning and direction. From the initial concept, feature design, and code implementation to testing, maintenance, and documentation, every major stage of the project was developed and supervised by tcib_cat.

The project originated from practical needs encountered during actual use. Its purpose is not only to address specific technical problems, but also to provide server owners and developers with a stable, practical, and accessible solution. Throughout development, the project’s features, code structure, performance, and compatibility were repeatedly reviewed and improved based on testing and real-world feedback.

The completion and continued improvement of this project would not have been possible without the users and developers who participated in testing, reported issues, and provided suggestions. Their feedback, ideas, and usage experiences have contributed greatly to the development of the project. We would therefore like to express our sincere appreciation to everyone who has supported, tested, and used it.

Although this project is provided free of charge, its source code and development work may not be claimed, reused, or redistributed without proper acknowledgement. Anyone who uses, references, or modifies the project’s code must comply with the applicable license terms and clearly credit the original author and project.

The use of this project primarily for profit is discouraged. These plugins are made available to servers free of charge, and their development does not provide the original developers with any substantial financial return. Repackaging, reselling, or commercially exploiting work that was freely shared by the original developers may negatively affect motivation to continue maintaining and releasing open-source projects. We therefore ask users and developers to respect the non-commercial spirit in which this project is provided.

Original copyright notices, author information, and attribution must not be removed, hidden, or altered. The project, or any modified version of it, must not be renamed, repackaged, or presented as an independently developed work through minor code changes, additional features, or other forms of redistribution.

Developers who create derivative works based on this project should clearly identify the original project and author, while also describing the changes or additions they have made. Respecting the work of the original developer is not only a basic form of acknowledgement, but also an essential part of maintaining a healthy and sustainable open-source community.

As this project is primarily developed and maintained through personal time and effort, it may still contain limitations or areas that require improvement. We will continue to improve the project within our capabilities, but we cannot guarantee support for every use case or provide unlimited long-term technical assistance. We appreciate your understanding and encourage users to report issues and suggestions through the appropriate channels.

Finally, we would once again like to thank everyone who has supported and followed this project. We hope it can continue to provide meaningful assistance to its users, and that every openly shared development effort receives the recognition and respect it deserves.

<div align="right">

**Lead Editor and Developer: tcib_cat** <br>
**Development Team: FreshChromatic**

</div>
