# Skills workspace

This directory provides a standard `skills/` path for runtime tooling (e.g. sync and loaders)
while keeping skill source folders at the repository root.

Each entry is a symlink to the corresponding top-level skill package directory. If you add a
new skill package at the repo root, add a matching symlink here so the runtime can discover it.
