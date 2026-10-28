---
name: confluence-docs
description: >
  Mandatory rules for writing or changing documentation in ms-clp-blp. All official
  documentation lives in docs/confluence/, is declared in confluence-manifest.yml, and is
  organised BY FEATURE: one page per thing the service does (BLP/CLP page, carousels, category data…)
  holding its implementation AND its production support (config, where to look in prod,
  symptoms, cURLs). Invoke for ANY documentation task: "document this endpoint", "write the
  docs for", "update the documentation", "add an ADR", "the docs are out of date", "explain X
  in the docs", "new page", "runbook", "support docs", "how is X configured", "where do I
  look in prod for X", or whenever code changes make an existing page wrong. Also invoke
  before creating ANY .md file anywhere in the repo, to decide whether it belongs in the
  published tree or outside it.
---

# ms-clp-blp Documentation Rules

Documentation is a published product, not loose files. Everything official lives in
`docs/confluence/`, is declared in `confluence-manifest.yml`, and is pushed to the Confluence
space of ms-clp-blp by `scripts/publish_confluence.sh`. **The space does not exist yet:** its key is
the placeholder `TODO_CLP_BLP_SPACE` in `confluence-manifest.yml`, `scripts/publish_confluence.sh`
and `scripts/.envconfluenceexample` until it is created.

**Why the rules below exist.** ms-home's team stopped reading its first doc tree: it was organised
by technical layer (API / Integrations / Runtime / Diagrams), so one feature was spread over up to
32 pages and every question cost five to eight jumps. The fix is structural — organise by feature,
and put everything about a feature on its page — and these rules keep it that way.

## Organisation: one page per feature

| The content is about… | It goes in | Example |
|---|---|---|
| One thing the service does for someone (a block, an endpoint family, a capability) | `funcionalidades/<slug>.md`, `type: feature`, using the template below | `funcionalidades/accesos-directos.md` |
| Something that applies to **two or more** features | `plataforma/<slug>.md` | profiles, flag precedence, error codes, architecture |
| A decision and its trade-off | an ADR in `reference/adrs.md`, with `{#adr-NNN}` on its heading | ADR-069 |
| Throwaway analysis, a migration plan, a gap study | `docs/` root, **outside** the tree; first line says it is not official documentation | — |

**Single source of truth is per feature, not per concept.** Every fact lives on the page of the
feature that owns it — including that feature's cURLs, diagram, config keys, contract and support
runbook. A Plataforma page holds only what genuinely applies to several features, and a feature page
links to it with an anchor instead of copying it. Never create a page that collects one *kind* of
thing across features (all cURLs, all diagrams, all contracts): that is exactly what made the old
tree unreadable.

**Before adding a page, try a section.** A new block of an existing feature, a new provider of the
carousels, a new symptom: all are sections of an existing page. A new page is justified only for a
new feature someone would search for by name. Fewer, complete pages beat many partial ones.

> Transitional state (2026-10-14): the old layer-based tree is still published while the feature
> pages are piloted (`confluence-manifest.pilot.yml`). New and updated content goes into the feature
> pages; touch a legacy page only to keep it correct until it is archived.

## Everything is written in Spanish

Page bodies are Spanish. English survives only in identifiers that exist in the code or on the
wire: class and package names, HTTP headers, JSON fields, enum values, circuit-breaker names, config
keys, curl commands — plus the front matter keys and the manifest `title`, which are the publishing
contract, not prose. Do not translate an identifier to make a sentence flow.

## The feature page template (`type: feature`)

The dry run fails a `type: feature` page whose `#` sections are not exactly these, in this order, or
whose `# Soporte` lacks these four `##` subsections in order. The fixed shape is what lets a reader
jump straight to their part.

```markdown
---
id: funcionalidades.<slug>
title: "<Título en español>"          # = manifest title
type: feature
status: current
owner: equipo-search-browse
lastReviewed: 2026-10-14
sources:
  - src/main/java/com/liverpool/ms_clp_blp/landing/<area>/
tags: [<block-uid>, <providers>]
audience: [frontend, backend, qa, soporte, sre]
summary: >
  Una o dos frases: qué ve la persona y qué sistemas lo hacen posible.
---

# En 30 segundos
(4 bullets max: CMS block, endpoint, systems, what happens when it fails)

# Cómo funciona
(```mermaid diagram + 3-5 numbered steps)

# Implementación
(## En el CMS — fields per variant · ## El endpoint — body, response in a collapsed fence,
status/reason tables · rules and edge cases as short bullets)

# Configuración
(ONE table: Clave | Dónde vive | Valor en prod | Efecto — flags, env vars, secrets, timeouts)

# Soporte
> [!IMPORTANT]
> Todo lo de esta sección asume las variables de [Cómo usar los cURL](../funcionalidades.md#como-usar-los-curl).

## Dónde mirar en producción
## Síntomas y acciones
## cURLs de verificación
## Escalamiento

# Referencias
(ADRs linked by anchor, code paths)
```

Copy the structure of `funcionalidades/paginas-blp-clp.md`; it is the reference implementation.

### What the Soporte section must contain

Written for someone on call who has never read the code:

- **Dónde mirar en producción** — a runnable Mongo query (`search_index.ms_clp_blp_configuration` for
  the feature's per-brand flags), the Cloud Logging filter **that works
  today** (prod logs are `textPayload`; say so), the event codes, the `upstream` and breaker names.
  Verify each query against prod with the read-only MCP tools before writing it.
- **Síntomas y acciones** — a table: Síntoma (as the reporter describes it) | Causa probable | Cómo
  confirmarlo (the exact field, reason or log line) | Acción. Include "it's expected" rows: they
  save the most time.
- **cURLs de verificación** — copy-paste, using only the variables declared once on the
  Funcionalidades page (`$MS_CLP_BLP_URL`, `$MS_CLP_BLP_SESSION_ID`), always with `x-brand-id` and
  `x-channel` (without `x-channel` the session is treated as guest). Each cURL says what to expect.
- **Escalamiento** — who owns each external dependency and what evidence to attach.
- **Never** a secret value: name the env var or Secret Manager entry instead. Config values that are
  not secrets (URLs, timeouts, defaults) are written out.

## Writing for readers who skim

- **Answer first.** The first line of every section states the conclusion; detail follows.
- **Tables for anything with more than two attributes** (config, reasons, symptoms). Prose for the
  why, in short paragraphs.
- **No history in feature pages.** "Antes de ADR-043…", "Actualizado 2026-08-02…", "hasta el
  2026-09-01…" belong in the changelog or the ADR. A feature page describes what is true now.
- **Size budget: ~450 lines** for a feature page; the dry run warns above it. Over budget usually
  means a Plataforma topic leaked in, or a long JSON that should be a collapsed fence.
- One diagram per feature is usually enough. It goes in `# Cómo funciona`, not on a separate page.
- Write what the code does, and derive it from the code. No aspirational documentation. When the code
  and an old page disagree, the code wins and the old page gets fixed.

## Front matter — required on every page in `docs/confluence/`

```yaml
---
id: funcionalidades.paginas-blp-clp     # stable slug, never changes, even if the title does
title: "Páginas BLP y CLP"             # MUST equal the `title` in the manifest
type: feature                          # feature | overview | reference | how-to | api | explanation | adr
status: current                        # current | draft | deprecated
owner: equipo-search-browse
lastReviewed: 2026-10-14               # absolute ISO date a human confirmed it matches the code
sources:                               # code this page is derived from — the update trigger
  - src/main/java/com/liverpool/ms_clp_blp/landing/
tags: [blp, clp, page_blp, page_clp]
audience: [frontend, soporte]          # optional, shown in the header panel
summary: >                             # required for type: feature; replaces the old preamble
  Qué es, en una o dos frases.
related: []                            # optional
---
```

`id`, `title`, `type`, `status`, `owner`, `lastReviewed`, `sources` and `tags` are required on every
page; `summary` is required on feature pages and recommended on all new ones. The publisher turns
`summary` + `audience` + `lastReviewed` into a header panel and adds a table of contents, so a page
with `summary` does **not** write `# Objetivo / # Alcance / # Audiencia`. A page without `summary`
(the old format) still must open with that preamble.

- **`sources`** links code to docs: when a path under it changes, this page may be wrong.
  `grep -rl "landing/blocks/productslist" docs/confluence --include="*.md"` finds the pages to fix. Use
  `sources: [none]` only for pages derived from nothing in the repo.
- **`id`** survives a rename; **`title`** is the update key in Confluence (renaming creates a new
  page — change `file` in the manifest to move content, never `title`).
- **`tags`** become Confluence labels together with `type-*` and `status-*`; feature pages also get
  `funcionalidad` and `soporte` automatically. Reuse existing tags
  (`grep -h "^tags:" -r docs/confluence`).
- **`lastReviewed`** is bumped when a human confirms the page against the code, not for a typo fix.

## Syntax the publisher understands beyond Markdown

| You write | Confluence shows |
|---|---|
| `> [!NOTE]`, `[!TIP]`, `[!IMPORTANT]`, `[!WARNING]` (first line of a blockquote) | info / tip / note / warning panel |
| `## Heading {#my-anchor}` | a linkable anchor |
| `[text](../funcionalidades/accesos-directos.md#soporte)` | a link to that exact section. Every `#` section of a page with `summary` has an automatic anchor: lowercase, no accents, hyphens (`#como-funciona`, `#configuracion`, `#soporte`) |
| ```` ```json collapse title="Respuesta completa" ```` | a collapsed, titled code block — use it for long JSON |
| ```` ```mermaid ```` | the diagram rendered as an image (attached to the page) with its source collapsed below |
| a page with children in the manifest | a `children` macro at the end: index pages need no hand-kept list |

Mermaid is now actually rendered, so a diagram must compile: write `#lt;`, `#gt;`, `#amp;` instead
of HTML entities, and never put a literal `;` in the text of a `sequenceDiagram` (it ends the
statement — use a comma). The dry run catches both; check anything else at mermaid.live.

The dry run fails on a link to an anchor that does not exist in the target page. ADR headings carry
`{#adr-NNN}`: link decisions as `[ADR-042](../reference/adrs.md#adr-042)`.

Still forbidden: raw HTML (`<details>`, `<div>`, `<img>`, tables in HTML…), shields.io badges, links
outside the published tree (cite the path as `` `docs/parity/foo.md` `` instead).

### Line breaks: wrap freely, break deliberately

Prose is hard-wrapped at ~100 columns; the publisher joins soft-wrapped lines into one paragraph.
Deliberate breaks: **two trailing spaces** → `<br/>` (used in `adrs.md` between `**Status:**` and
`**Context:**` — never strip that whitespace), a **blank line** → new paragraph, `<br/>` inside a
mermaid label. Fenced and indented code keep their newlines.

## Confluence is editable — and an edit freezes the page

The publisher seals what it wrote in a content property (`msClpBlpSync`). On the next run, a page a
human edited is **kept** and no longer receives repository updates (`conservada`, or `conflicto` if
the repo changed too) until someone runs `--force-page "<title>"`. Because feature pages are large
and hold the support runbook, **support edits go through the Markdown** (a PR), not the Confluence
editor; an edit made in Confluence is ported back to the Markdown and the page re-published.

| Estado (`--status`) | Qué significa |
|---|---|
| `nueva` | No existe todavía; se creará |
| `sin-cambios` | Idéntica a lo último publicado: no se escribe |
| `actualizar` | El repo cambió y nadie la editó: se publica |
| `conservada` | La editó una persona; el repo no ha cambiado. No se toca |
| `conflicto` | La editó una persona **y** el repo cambió. No se toca, se reporta con su URL |

## Recipes

### New feature page

1. Copy `funcionalidades/accesos-directos.md` to `funcionalidades/<slug>.md`; keep the six sections.
2. Fill it from the code and verify the Soporte queries against prod (read-only MCP tools).
3. Add it under `Funcionalidades` in the manifest, `title` matching the front matter.
4. `scripts/publish_confluence.sh --dry-run --strict`, then `--status`, then publish.

### Code changed — which docs are now wrong?

`grep -rl "<changed path>" docs/confluence --include="*.md"`, fix every page that comes back, bump its
`lastReviewed`. A change to an endpoint, a contract field, a config key, a flag, a log event or an
integration almost always touches a feature page's Implementación, Configuración **and** Soporte.

### Retiring or moving pages

Pages removed from the manifest stay alive in Confluence (the publisher never deletes). After
publishing the new tree, `scripts/publish_confluence.sh --archive-orphans --status` lists them and
`--archive-orphans` moves them under «Archivo (obsoleto)», labelled `obsoleta`. To retire a single
page instead, set `status: deprecated` and say at the top what replaced it.

## Before you finish

```bash
scripts/publish_confluence.sh --dry-run --strict   # no network: manifest, front matter, template,
                                                   # links, anchors, raw HTML; warns on page size
scripts/publish_confluence.sh --status             # what a publish would do, page by page
```

Full tooling reference: [`scripts/README.md`](../../../scripts/README.md).
