---
title: "AI Response Performance: Latest Results"
description: How well the AI reads uploaded price lists and contact lists, measured against hand-checked answers. Updated after every run.
---

# AI Response Performance: Latest Results

> **Latest run: 2026-11-25**, at the product's default settings, after the customer language round. Only the 23 Customer Book cases were rerun. The 10 Catalogue cases keep their round 3 results from the same day and are marked as not rerun.
>
> This page is updated after every run of the checks. The rules behind the checks are in [AI Response Performance]({{ site.baseurl }}{% link epics/07-ai-response-performance.md %}).

When a user uploads a picture, PDF or spreadsheet, an AI (artificial intelligence) model reads the items or customers out of it. The checks send a fixed set of real uploads to the AI and compare each answer with a **golden answer**: the correct answer, checked by hand. There are 33 cases: 10 for the Catalogue and 23 for the Customer Book.

On this page a case counts as **failing** when the AI's answer differs from its golden answer in any way. The checks themselves never stop on a difference; they only report it.

## Headline numbers

| Measure | Latest result | What it tells you |
| --- | --- | --- |
| **Data accuracy (notes excluded)** | **98.0% of field values correct** (8,698 of 8,879) | How accurately the AI read the actual data, leaving out its written notes. Higher is better. |
| **Cases with every field correct** | **28 of 33 cases (85%)** | Every name, price, code, phone, address part and language matched. Higher is better. |
| **Exact-match rate** | **17 of 33 cases (52%)** | The AI's answer matched the hand-checked answer exactly, notes included. Higher is better. |
| **Cases with differences** | **16 of 33 (48%)** | At least one value read differently. Lower is better. |
| **Total differences** | **216** | Catalogue 93 (round 3, not rerun), Customer Book 123. Lower is better. |
| **Consistency** | **Not measured in this run** | The same file read twice gives the same answer. Customer Book was read only once after the language round. The latest measured figure, round 3, was 24 of 31 cases (77%), with 90 differences between the two runs. Higher is better. |

Why the two views differ: of the 16 cases with differences, 11 differ only in how the AI worded its notes, not in the data it read.

Two cases hold most of the differences: the dense price-list photo (66) and the 347-row customer export (111), together 177 of the 216 (82%).

The golden answers now hold 430 more field values than in round 3, because each customer found now has a language. So the Customer Book accuracy is measured over 5,049 values instead of 4,619.

## Data accuracy (notes excluded)

This view looks only at the data the AI read, and leaves its notes out entirely.

- A **field value** is each name, price, code, phone number, address part, language, flag and count in the golden answer.
- A **wrong value** is one that is changed, added or missing compared with the golden answer. An extra or missing record counts once for each value inside it.

| | Cases with every field correct | Field values correct |
| --- | --- | --- |
| **All** | **28 of 33 (85%)** | **98.0%** (8,698 of 8,879) |
| Catalogue (round 3, not rerun) | 8 of 10 (80%) | 99.2% (30 wrong of 3,830) |
| Customer Book | 20 of 23 (87%) | 97.0% (151 wrong of 5,049) |

| Upload type | Cases with every field correct | Field values correct |
| --- | --- | --- |
| Pictures | 14 of 18 (78%) | 98.8% (32 wrong of 2,766) |
| PDFs | 5 of 5 (100%) | 100% (0 wrong of 1,125) |
| Spreadsheets | 9 of 10 (90%) | 97.0% (149 wrong of 4,988) |

Only five cases have any wrong field:

| Case | Wrong values | Field values correct | What goes wrong |
| --- | --- | --- | --- |
| Customer Book spreadsheet-4, 347-row export | 149 of 3,878 | 96.2% | Cities taken from the State or Region column, and extra addresses. Most of the rise from 60 wrong in round 3 comes from 18 extra addresses and 12 such cities |
| Catalogue image-2, dense price-list photo (round 3, not rerun) | 27 of 1,504 | 98.2% | Names, product-code digits and prices |
| Catalogue image-1, price-list picture (round 3, not rerun) | 3 of 583 | 99.5% | A few misread values |
| Customer Book image-3, contact-list picture | 1 of 56 | 98.2% | The printed name "Вочкова" changed to "Волкова", as in round 3 |
| Customer Book image-6, contact-list picture | 1 of 70 | 98.6% | A city left out. New in this run |

Every other case is 100% field-correct, including every PDF and every spreadsheet apart from the 347-row export.

### Consistency, notes excluded

The same file read twice, on the same 31 cases (the 347-row export and one unrelated spreadsheet are not covered). Consistency was not measured after the customer language round, so round 3 is the latest measured figure. In round 3 the second run was stopped early, which is why only 31 of the 33 cases are covered.

| Round | Field differences between the two runs | Cases with identical fields |
| --- | --- | --- |
| **Round 3 (latest measured)** | **23** | **29 of 31 (94%)** |
| Round 3a | 77 | 26 of 31 |
| Round 2 | 27 | 27 of 31 |

In round 3 only two cases varied: Catalogue image-2 (21) and image-1 (2). No phone number or city varied in the cases covered.

## Breakdowns, notes included

### By check

| Check | Cases | Exact match | With differences | Differences |
| --- | --- | --- | --- | --- |
| Catalogue (round 3, not rerun) | 10 | 3 (30%) | 7 | 93 |
| Customer Book | 23 | 14 (61%) | 9 | 123 |
| **All** | **33** | **17 (52%)** | **16** | **216** |

### By upload type

| Upload type | Cases | Exact match | With differences | Differences |
| --- | --- | --- | --- | --- |
| Pictures | 18 | 9 (50%) | 9 | 90 |
| PDFs | 5 | 3 (60%) | 2 | 9 |
| Spreadsheets | 10 | 5 (50%) | 5 | 117 |

## Trend

Each round changed the AI's reading instructions to make answers more correct and more consistent. Rows are never overwritten; each run adds one.

| Round | Date | Field values correct (notes excluded) | Total differences | Exact matches | Cases read the same twice | Differences between two runs |
| --- | --- | --- | --- | --- | --- | --- |
| Start: first stored results | 2026-11-24 | not measured | 598 | 13 of 33 | not measured | not measured |
| Round 1 | 2026-11-24 | not measured | 282 | 18 of 33 | not measured | not measured |
| Round 2 | 2026-11-24 | not measured | 232 | not recorded | 21 of 33 (20 of 31) | 172 (64 on the 31) |
| Round 3a, before a fix for tables side by side | 2026-11-25 | not measured | 330 | not recorded | 22 of 33 (21 of 31) | 210 (120 on the 31) |
| Round 3 | 2026-11-25 | 98.9% | 212 | 18 of 33 | 24 of 31 | 90 on the 31 |
| **Customer language round, Customer Book only rerun (Catalogue at round 3)** | **2026-11-25** | **98.0%** | **216** | **17 of 33** | **not measured** | **not measured** |

To compare consistency like with like, use the figures on the same 31 cases, in brackets: 20, then 21, then 24 cases read the same twice.

From the customer language round on, field values include each customer's language, 430 more values. So its accuracy figure is not strictly like for like with round 3.

## Every case, latest run

**Status**, notes included: ✅ exact match (0 differences) · ⚠️ small (1 to 5) · ❌ large (more than 5). **Fields correct** leaves notes out: ✅ means every field value is right.

### Catalogue (round 3, not rerun)

| Case | What it is | Differences | Status | Fields correct |
| --- | --- | --- | --- | --- |
| image-1 | Price-list picture, sent with a processing message (1 kg items become 12 kg per box) | 13 | ❌ | 99.5% (3 wrong of 583) |
| image-2 | Dense supplier price-list photo, three columns, 125 priced rows | 66 | ❌ | 98.2% (27 wrong of 1,504) |
| image-3 | Unrelated photo, must give no items | 0 | ✅ | ✅ |
| pdf-1 | PDF price list | 8 | ❌ | ✅ |
| pdf-2 | Unrelated meeting-notes PDF | 0 | ✅ | ✅ |
| spreadsheet-1 | 30-row CSV price list | 2 | ⚠️ | ✅ |
| spreadsheet-2 | Excel price list (.xls) | 1 | ⚠️ | ✅ |
| spreadsheet-3 | Excel price list (.xlsx) | 2 | ⚠️ | ✅ |
| Note shortening | spreadsheet-1 read in two groups, repeated notes shortened | 1 | ⚠️ | ✅ |
| spreadsheet-4 | Unrelated staff-rota CSV | 0 | ✅ | ✅ |

### Customer Book

| Case | What it is | Differences | Status | Fields correct |
| --- | --- | --- | --- | --- |
| image-1 | Handwritten contact list (English) | 1 | ⚠️ | ✅ |
| image-1, with a message | The same picture, sent with "omit phone numbers, keep everything else" | 1 | ⚠️ | ✅ |
| image-2 | Handwritten contact list (Greek) | 0 | ✅ | ✅ |
| image-3 | Contact-list picture | 3 | ⚠️ | 98.2% (1 wrong of 56) |
| image-4 | Contact-list picture | 0 | ✅ | ✅ |
| image-5 | Business card | 0 | ✅ | ✅ |
| image-6 | Contact-list picture | 2 | ⚠️ | 98.6% (1 wrong of 70) |
| image-7 | Contact list with handwritten notes | 2 | ⚠️ | ✅ |
| image-8 | Contact-list picture | 1 | ⚠️ | ✅ |
| image-9 | Contact-list picture | 1 | ⚠️ | ✅ |
| image-10 | Contact-list picture | 0 | ✅ | ✅ |
| image-11 to image-14 | Four unrelated pictures, must give no customers | 0 each | ✅ | ✅ |
| pdf-1 | Typed customer directory, 25 entries over two pages | 0 | ✅ | ✅ |
| pdf-2 | Scanned handwritten contact lists (English and Greek) | 1 | ⚠️ | ✅ |
| pdf-3 | Unrelated PDF of room-temperature readings | 0 | ✅ | ✅ |
| spreadsheet-1 | Small CSV with two contacts | 0 | ✅ | ✅ |
| spreadsheet-2 | Excel contact list (.xls) | 0 | ✅ | ✅ |
| spreadsheet-3 | Excel contact list (.xlsx) | 0 | ✅ | ✅ |
| spreadsheet-4 | 347-row contact-app export, read in several groups, repeated notes shortened | 111 | ❌ | 96.2% (149 wrong of 3,878) |
| spreadsheet-5 | Unrelated CSV of water-meter readings | 0 | ✅ | ✅ |

## What is happening

- **The dense price-list photo is the hardest case** (Catalogue image-2, 66 differences, round 3, not rerun). The AI reads some names, product-code digits and prices differently from what is printed, and the general notes that follow change with them. It is also the least consistent case: 64 of the 90 differences between the two round 3 runs come from it.
- **The 347-row customer export still takes cities from the wrong column** (Customer Book spreadsheet-4, 111 differences). Sometimes the city is filled from the State or Region column, against the rule, and the addresses then group differently. This run it added 18 extra addresses and took 12 such cities. Its consistency has never been measured.
- **Customer language is read correctly.** All 430 customers found got the right language. The only miss is one missing note, "Language set to English by default", on row 254 of the 347-row export.
- **A city left out** in one contact-list picture (Customer Book image-6). This is new in this run.
- **Small wording drift in the Catalogue spreadsheets** (round 3, not rerun). The note for a price marked TBD is worded in different ways, and the AI sometimes says the currency came from the column header and sometimes from the price list header.
- **The business card** (Customer Book image-5) matched exactly this run. In round 3, its slogan lines were reported with or without "contact details beside it".

These are known and accepted for now: see [the AI still breaks some reading rules]({{ site.baseurl }}{% link epics/07-ai-response-performance.md %}#7-at-default-settings-the-ai-still-breaks-some-reading-rules), [differences in note wording]({{ site.baseurl }}{% link epics/07-ai-response-performance.md %}#3-differences-in-note-wording-fill-the-patch) and [one skipped item can make many later items differ]({{ site.baseurl }}{% link epics/07-ai-response-performance.md %}#8-one-skipped-or-extra-item-can-make-many-later-items-differ).

## How to read the numbers

- A **difference** is one value the AI left out, added or changed compared with the golden answer.
- Differences show size, not severity. One misread code is one difference, but one skipped item can count as many, because the items after it shift.
- **Exact-match rate** is the headline measure. **Consistency** shows how much the AI varies when it reads the same file again; a low score means results can change from upload to upload even when nothing else changed.

## How this page is updated

- A developer runs the checks by hand with the AI key, at the product's default settings, only with the go-ahead. Each run costs AI credits.
- After each run, update the date at the top, the headline numbers, the two breakdown tables and the per-case tables, and add a new row to the trend table. Never overwrite or delete an earlier trend row.
- The numbers come from the stored differences in the repository, one per case, written by the run at default settings. Consistency needs a second run of the same files, compared with the first.
- Both views come from the same stored differences: the notes-excluded view simply ignores the differences in notes, and counts the field values in each golden answer.

{% include abbreviations.md %}
