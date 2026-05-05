# Obsidian vault — quick reference

Keep this open while operating on the vault.

## Common operations

| Goal | Tool / shell |
|---|---|
| List recent notes | `bash scripts/helper.sh /path/to/vault` |
| Find by tag | `grep -rn '#tagname' /path/to/vault --include='*.md'` |
| Find by filename | `find /path/to/vault -iname '*keyword*.md'` |
| Read a note | use the `read_file` tool |
| Create / append a note | use the `write_file` tool with `.md` suffix |

## Wikilink syntax

Notes link with double brackets:

```markdown
See [[Other Note]] for context.
[[Folder/Subnote|display text]] also works.
```

When creating notes that should be discoverable, link them from at least
one existing note so the graph stays connected.
