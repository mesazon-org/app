---
title: Epic Standards
---

# Epic standards

How epics are written and how to read one. [Part 2](#part-2--the-skeleton) is the skeleton to copy when starting a new epic.

This file is not published with the site. It is for whoever writes or reviews an epic.

---

## Part 1 — The standards

### What an epic is

An epic covers one area of the product end to end, described from the outside in: what a person can do, in what order, the situations each step has to handle, and the rules behind them.

The audience is **non-engineers**. Someone who has never opened the codebase should read it start to finish and understand what the product does. `agent-docs/features/` stays the engineering source of truth for endpoints, types, files and tests; an epic never duplicates that.

### The two rules

**Plain, simple English.** Short sentences, everyday words. Say "wrong code" before "invalid OTP". No Scala, type, class or file names. No endpoint paths. No internal jargon and no unexplained abbreviations. Describe what a person can see and do, not the implementation that produces it. If a sentence only makes sense to someone who has read the code, rewrite it.

**Always true of the code.** Every stage name, error code, field rule, limit and business rule must match what the code actually does today. Check against the matching `agent-docs/features/*.md` doc and, where that does not settle it, the code itself. Update the epic whenever the feature changes, in the same change, not later. A confidently wrong epic is worse than a missing one.

### File, name and front matter

Name the file with a two-digit number in reading order: `01-user-onboarding.md`, `02-forgot-password.md`. The sidebar sorts epics by filename, so the number is what puts them in journey order rather than alphabetical order.

Keep the number out of `title:`. That is the display name, and it appears in the sidebar, the browser tab and the home page.

Front matter is required. Jekyll only builds files that have it; without it the page is served as a raw file instead of rendering.

A new epic is listed in two places, updated together: the **Epics** section of `AGENTS.md`, and `pages/index.md`.

### Acronyms

Spell an acronym out on first use. Any acronym new to the product also goes in `pages/glossary.md` **and** in `pages/_includes/abbreviations.md`, which gives every later use a hover tooltip. Keep the two saying the same thing.

Every epic ends with the abbreviations include. It renders nothing and turns every acronym in the page into a hoverable definition.

### Diagrams

A mermaid diagram after the overview is optional. Add one only when a journey is tangled enough that a picture beats the prose — several branches, or a loop back to an earlier step. A diagram that restates steps the reader is about to read costs more space than it earns, so most epics do not need one.

Keep it small. If it does not fit on a screen it is doing too much: cut it back to the one part that is hard to follow in words.

### How each step is laid out

Every step repeats the same four sections, in this order:

| Section | What goes in it |
|---|---|
| Business Scenarios | Every situation the step must handle, awkward ones included |
| Requirements | The rules that fall out of those scenarios |
| Request / Response / Outcome | What goes in, what comes back, and what it changed |
| Http Error Responses | What can go wrong |

Scenarios come first on purpose. Work them out, and the requirements follow.

### Field tables

Request and response fields use five columns: **Field Name**, **Type**, **Constraint**, **Required**, **Description**.

- **Type** is the real type from the API contract — `String`, `Long`, `UUID`, `Boolean`, or a named enum such as `OnboardStage`. Never a loose word like "Number" or "Object". Keep it to one column; do not split it per language.
- **Constraint** is the validation rule, in words: `1–255 characters, trimmed`, `Lowercase letters, digits and hyphens; max 63 characters`. Use `—` when a field has no rule beyond its type. Constraint never says "required" or "optional" — that is the next column's job. **Derive it from the code, never from intent** — see below.
- **Required** is `✅` or `❌`, nothing else.
- **Description** says what the field is *for*. A reader should be able to tell which values carry into the next step.

A field holding an object, or a list of them, names its own type rather than saying `Object`: `PhoneNumber`, or `PhoneNumber[]` for a list. Give that type its own small table underneath, headed with the type name, instead of nesting fields inside the parent table. Define each shape once per epic and refer to it by name everywhere else — a table with two levels of field in it is hard to read and impossible to point at.

Say `Request is empty.` or `Response is empty.` when a step sends or returns no fields. Do not invent a table to fill the space, and do not describe side effects there — those belong in **Outcome**.

### Where constraints come from

Constraints are **read out of the validation layer**, not written from what the field is supposed to hold.

A field's rule is enforced in **two places, and you need both**:

1. **The Iron refined types in the `backend/domain` module** — the predicates in `domain.scala` and the newtypes in `gateway/Newtypes.scala` that bind them to fields. Translate the predicate into words; never paste the pattern. `Trimmed & MinLength[1] & MaxLength[255]` becomes `1–255 characters, trimmed`.
2. **The validator in `validation/`** — which often applies a library check the predicate cannot express, before or after constructing the newtype. `EmailValidator` runs JMail, which enforces the RFC email standard. `PhoneNumberUtil` checks a number is real for its country. These are the stricter, more meaningful rule, and a reader needs them.

Read the validator first and the predicate second. Stopping at the predicate is the easiest way to get this wrong: the email predicate is only `^[^@\s]+@[^@\s]+\.[^@\s]+$` with a 255-character cap, which looks like the whole rule and is not — the library in front of it is doing the real work.

Two traps, both of which have already produced wrong epics here:

- **Do not stop at the predicate when a validator wraps it.** An email field was briefly documented from its predicate alone, dropping the RFC standard the library actually enforces. The epic then described the product as looser than it is.
- **Do not describe what the system *generates* when the field is an input.** The one-time passcode predicate is `^[A-Z0-9]{6}$` — any six uppercase letters or digits are accepted. The "two to four letters and two to four digits" shape is what the generator produces. That belongs in a requirement about how codes are made, not in the constraint for a field someone types into.

When the epic and the validation layer disagree, the code wins and the epic is wrong.

### Reading the Outcome section

**Outcome** is what actually changed once the step succeeded: the stage the person moves to, what was saved or deleted, what was sent, what was revoked. It also covers the unhappy paths that change something — an expired code that gets deleted, a wrong code that deliberately changes nothing.

This is where a step with an empty response earns its place. The response tells a reader nothing, so the outcome has to tell them everything.

### Reading Known gaps and open questions

Everything above that section describes what the product does **today**. That section is the opposite: situations the epic has not decided yet, behaviour that looks wrong or unintended, and questions needing a product answer before anything is built.

Nothing in it exists. Each entry says what happens today, why it matters, and ends with a **To decide:** line naming the question someone has to answer.

Reviewing an epic against the code is how the section gets filled:

- Behaviour the code has but the epic never described → **a requirement**, written above.
- Behaviour the epic assumes but the code never implements, or that looks unintended once you read it → **a gap**, written here, never as though it already works.

Drop the section when there is nothing in it. An empty heading is worse than none.

### Missing tests are not gaps

A **gap** is a product decision that has not been made. A **missing test** is engineering work on behaviour that is already decided. They are different things and live in different places.

When a review of an epic turns up behaviour with no acceptance test behind it, record it in [`agent-docs/acceptance-test-gaps.md`](../../agent-docs/acceptance-test-gaps.md) — not in the epic. Each entry there names the behaviour, the epic scenario it maps to, which spec it belongs in, and what would slip through silently without it. Delete the entry in the PR that closes it.

Writing a new epic is a good moment to do that audit, because the scenarios have just been enumerated and can be checked off against the spec one by one.

---

## Part 2 — The skeleton

Copy everything below into `pages/epics/<NN>-<name>.md` and fill it in. Delete the guidance comments as you go.

---

```markdown
---
title: Epic Title
---

# Epic Title

### Overview

One or two sentences: what the user can now do, and why it matters to the business.

<!-- OPTIONAL mermaid diagram here — see "Diagrams" in the standards. Most epics do not need one. -->

### Related / Out of scope

Three flavours, and the difference matters to a reader deciding where to look next. Use only the ones that apply.

- **Related** — adjacent epics a reader should know about. One line each saying what the relation is, not a restatement of that epic.
- **Out of scope** — something a reader might reasonably expect here, that exists but is covered elsewhere.
- **Not built yet** — something a reader might reasonably expect here that does not exist anywhere.

A reader should never have to guess whether an omission is deliberate or an accident.

### Requirements across the epic

Only what holds true for more than one step. Anything belonging to a single step goes in that step's own **Requirements** list. Keep this section short, or it turns back into a dumping ground for the whole epic.

Number every list plainly, `1.`, `2.`, `3.`. Do not invent requirement IDs.

#### Functional

What the product does, stated so a reader could check each one off. Rules spanning the whole journey — the order of the steps, what every step has in common, what carries between them.

1. ...

#### Non-functional

Constraints the flow depends on but does not state outright: rate limiting, retries on external sends, anti-abuse posture, environment-specific bypasses, data handling. If a business scenario implies one of these, state the rule here rather than leaving a reader to infer it.

1. ...

### User flow

Numbered list of the steps, in order, each linking to its section below. If the steps are not a single journey — a set of operations on one record, say — write one line saying so.

1. [Step name](#1-step-name)

### Prerequisites

Any concept a reader needs before the steps make sense. Keep it short and link out to the authoritative source rather than duplicating its detail.

### 1. Step name

**Who can reach this step: [...]**

Whatever gates the step — the onboard stages allowed, a required role, organization membership — or "anyone" when it is open to all.

- Short bullets: where the user comes from, what they do, where they go next.

#### Business Scenarios

Every situation this step must handle, including the awkward ones — repeat submissions, expired or reused codes, someone arriving at the wrong point. Number sequentially within the step.

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. ... | - ... |

#### Requirements

What this step must do, plainly numbered, drawn from the scenarios above. Keep them specific to this step.

1. ...

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| | | | | |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| | | | | |

**Outcome**

- ...

#### Http Error Responses

Check these against the errors the code really returns, codes and status alike. A plausible-looking wrong code is the easiest thing in an epic to get wrong and the hardest for a reader to catch. Include the ones that come from the step's own gate, not just validation.

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Form validation error |
| 500 | `INTERNAL_SERVER_ERROR` | Unexpected error |

<!-- Repeat the "### N. Step name" block for every step, numbering sequentially. -->

### Known gaps and open questions

Everything above describes what the product does today. Nothing in this section exists yet; each one needs a product answer before it can be built.

One `####` subsection per gap: what happens today, why it matters, then a **To decide:** line. Number them, and do not reuse the step numbering style — a gap is not a step. Drop the whole section when there is nothing in it.

#### 1. Short sentence naming the gap

What happens today, and why it matters.

**To decide:** the question someone has to answer.

{% raw %}{% include abbreviations.md %}{% endraw %}
```
