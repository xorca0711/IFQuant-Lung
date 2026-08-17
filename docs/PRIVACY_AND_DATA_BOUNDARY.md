# Privacy and data boundary

> **Status: CURRENT.** This policy applies to the public repository and its
> working copies. It separates reusable source and approved provenance from
> local research material that must not be published.

## Allowed in the public repository

- Pipeline, launcher, aggregation, validation, and test source.
- Synthetic fixtures and machine-readable decision contracts.
- Scientific documentation and explicitly approved, non-human study provenance.
- Redacted validation reports whose paths are repository-relative or generic.

## Keep local or in access-controlled storage

- Raw or decoded microscopy data, tiles, masks, caches, and run directories.
- Reviewer packages, unblinding keys, and folders named
  `INTERNAL_DO_NOT_SEND` or `SEND_TO_REVIEWER`.
- Internal workspace links, private research notes, and presentation working files.
- User-home paths, temporary build paths, credentials, tokens, email addresses,
  and machine-specific local overrides.
- Any human-derived data or metadata unless a separate approved data-governance
  process explicitly authorizes publication.

## Study-specific material

This repository intentionally retains mouse-study identifiers and descriptive
pilot results as project provenance. They are not human personal information,
but they may still be confidential research material. Their public release
requires the project owner's institutional or laboratory authorization.

## Before publishing a change

1. Search the complete tracked tree for email addresses, user-home paths,
   internal workspace URLs, secrets, and raw-image extensions.
2. Inspect every newly tracked binary and generated report.
3. Keep local study overrides in `*.local.json` files and verify they remain
   ignored.
4. Confirm that review and unblinding directories are absent from the staged tree.
5. Use a GitHub no-reply address for future commits.

Git history may retain author metadata and files removed from the current tree.
Cleaning the current branch reduces active exposure but does not erase existing
public history.
