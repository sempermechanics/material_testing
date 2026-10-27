# ADR-032: This repo numbers its own tech debt, from TD-1

**Status:** Accepted, built (not merged)
**Date:** 2026-10-27
**Deciders:** app owner
**Relates to:** [FORK_SYNC](../ops/FORK_SYNC.md#td-and-adr-numbers),
[TECH_DEBT](../ops/TECH_DEBT.md)

## Context

Until 2026-10-27 this repo and semperdic-app shared one TD number space: before
taking a number, each read both registers and took the next free in both. It kept
breaking:

- TD-199 meant two things.
- #148 filed the Play App Signing key and reviewer access as TD-318 and TD-334, which
  semperdic-app had already used.
- The example data's TD-331–TD-333 clashed with semperdic-app's readiness rows.

The sync in #153 renumbered this repo's rows to TD-349–TD-353. The same day,
semperdic-app took TD-349 itself (semperdic-app#498), so the clash came straight back.
The numbers had also reached the 350s for a register of 60 rows.

## Decision

This repo's TD numbers are its own. On 2026-10-27 they restarted at TD-1:

- The 60 register rows took **TD-1–TD-60**, in their old order.
- Every other number cited anywhere here took **TD-61–TD-275**, also in old order.
  These are closed rows in CHANGELOG, ADRs and code comments, and rows only
  semperdic-app held, such as its readiness rows TD-318–TD-348. Once one series
  covered every citation, no old citation could be read as a new row.
- A cited range `TD-a–TD-b` became the runs of new numbers its members took.
- [td-renumber-2026-10-27.csv](../ops/td-renumber-2026-10-27.csv) maps each old
  number to its new one. Its `same_upstream` column says whether semperdic-app's
  register uses that old number for the same row. It is `no` for TD-199 and for
  TD-349–TD-353, this repo's last shared-scheme numbers. Git history, old PRs and
  semperdic-app's register keep the old numbers.
- The next free is TD-276. Nobody checks semperdic-app's register any more.

ADR numbers stay shared: both indexes still take the next number free in both.

A sync turns upstream TD numbers into this repo's with
`scripts/td_numbers.py translate`, run on the upstream diff before it is applied.
It maps only the `same_upstream` rows. Any other number becomes
`semperdic-app's TD-n`.

## Options

| Option | For | Against |
|---|---|---|
| Keep sharing, fix collisions as found | No change to syncs | It failed four times in two days, and every new row means reading both registers |
| A separate series for fork-only rows (e.g. `CL-1`) | Syncs unchanged, and only about 5 rows move | Two series in one register. The owner chose a plain restart |
| **Restart this repo at TD-1 (chosen)** | Small numbers that are this repo's alone, and no more collisions | About 1,600 citations rewritten once, and every sync translates upstream numbers |
| Restart both repos together | Numbers stay shared | Two large coordinated PRs, and every open PR in both repos conflicts |

## Consequences

- Every sync from semperdic-app runs `translate`, or its code comments and docs cite
  the wrong rows. The map covers every number cited here on 2026-10-27. A newer
  upstream number gets a row and a number here only if this repo carries it.
- `git log -S'TD-n'` finds a row's history from 2026-10-27 on. For earlier history,
  search its old number from the map.
- A PR written before the restart cites old numbers. Run its text through the map
  when it takes main: `translate` for upstream text, or the `old` → `new` columns for
  this repo's own.

## Action items

- [ ] Merge the restart before the next sync.
- [ ] semperdic-app: its next-free note says this repo numbers its own
      (sempermechanics/semperdic-app#495).
