# ADR-032: This repo numbers its own tech debt, from TD-1

**Status:** Accepted, built (not merged)
**Date:** 2026-10-27
**Deciders:** app owner
**Relates to:** [FORK_SYNC](../ops/FORK_SYNC.md#td-and-adr-numbers),
[TECH_DEBT](../ops/TECH_DEBT.md)

## Context

Until 2026-10-26 this repo and semperdic-app shared one TD number space: before
taking a number, each read both registers and took the next free in both. It kept
breaking. The fork's Play App Signing key and reviewer access were filed as TD-318
and TD-334, which semperdic-app had already used (material_testing#155 moved them
to TD-349 and TD-350). The example data's TD-331–TD-333 share their numbers with
three of semperdic-app's rows, and TD-199 had meant two things before that. The
numbers had also reached the 350s, although this register holds 61 rows.

## Decision

This repo's TD numbers are its own. On 2026-10-27 they restarted at TD-1:

- The 61 register rows took **TD-1–TD-61**, in their old order.
- Every other number cited anywhere here (closed rows in CHANGELOG, ADRs and code
  comments, and rows only semperdic-app held) took **TD-62–TD-252**, also in old
  order. Once one series covered every citation, no old citation could be read as
  a new row.
- A cited range `TD-a–TD-b` became the runs of new numbers its members took.
- [td-renumber-2026-10-27.csv](../ops/td-renumber-2026-10-27.csv) maps each old
  number to its new one. Git history, old PRs and semperdic-app's register keep the
  old numbers.
- The next free is TD-253. Nobody checks semperdic-app's register any more.

ADR numbers stay shared: both indexes still take the next number free in both.

A sync turns upstream TD numbers into this repo's with
`scripts/td_numbers.py translate`, run on the upstream diff before it is applied.
A number that isn't in the map becomes `semperdic-app's TD-n`.

## Options

| Option | For | Against |
|---|---|---|
| Keep sharing, fix collisions as found | No change to syncs | It has already failed three times, and both registers must be read for every row |
| A separate series for fork-only rows (e.g. `CL-1`) | Syncs unchanged; only about 6 rows move | Two series in one register. The owner chose a plain restart |
| **Restart this repo at TD-1 (chosen)** | Small numbers that are this repo's alone, and no more collisions | 1,600 citations rewritten once; every sync translates upstream numbers |
| Restart both repos together | Still shared | Two large coordinated PRs, and every open PR in both repos conflicts |

## Consequences

- Every sync from semperdic-app runs `translate`, or its code comments and docs cite
  the wrong rows. The map covers every number cited here on 2026-10-27. A newer
  upstream number gets a row and a number here only if this repo carries it.
- `git log -S'TD-n'` finds a row's history from 2026-10-27 on. For earlier history,
  search its old number from the map.
- PRs written before the restart cite old numbers. #154 merged first, and its
  references were translated when #155 took main (its TD-333 closed TD-59). #153 is
  translated when it takes main.

## Action items

- [ ] Merge the restart before the next sync, then run #153 through `translate`
      when it takes main.
- [ ] semperdic-app: replace its next-free note's "in both" with a pointer to this
      record (sempermechanics/semperdic-app#495).
