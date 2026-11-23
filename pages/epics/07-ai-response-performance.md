---
title: AI Response Performance
---

# AI Response Performance

> **Status: built.** The single Catalogue check runs its 10 cases against hand-corrected golden answers and fails on any difference. Every list is compared position by position, so order counts. A failing case prints only the comparison tool's indented list of differences, each placed by position, readable without colour and with no test id line. The old scored checks are removed: every Catalogue and Customer Book extraction check, both note-shortening checks, the scoring and all old golden answers. Customer Book's source files are kept. Only its rebuild is not built yet ([gap 2](#2-customer-book-has-no-golden-check-until-it-is-rebuilt)). Notes in italics starting *Before this rework* describe the removed checks.
>
> **First live run**, at the product's default settings: it took about 7 minutes, and every case got an answer, with no AI errors or timeouts. All 10 cases failed on differences. That is the agreed behaviour: the check reports differences, and the golden answers were not changed to make them pass (see gaps 3, 6 and 7).

### Overview

When the catalogue reads items out of a price list, an AI (artificial intelligence) model does the reading. This epic describes how the team checks the model's answer against a hand-checked correct answer. That check shows whether a change to the AI instructions made the answers better or worse, and how different AI models compare on the same sources.

The check covers Catalogue extraction from pictures, PDFs and spreadsheets, including unrelated files that must give no results. One spreadsheet case also proves that repeated notes are shortened when a spreadsheet is read in groups.

The check measures only the AI's own answer: what the AI returns before the product works on it. The product's own follow-up steps are fixed rules, not AI, and the product's existing automated tests already cover them. One example is finding duplicate names and product codes, then marking items that need attention. *Before this rework, the check ran the whole catalogue extraction and compared the final product response. Now it sends the source and the AI instructions straight to the AI, and compares the AI's answer.*

This is a check that developers run by hand. Customers never see it, and it changes nothing in the product itself.

### Related / Out of scope

- **Related** — [Catalogue]({{ site.baseurl }}{% link epics/06-catalogue.md %}) and [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}). Their extraction from pictures, PDFs and spreadsheets is the AI answer being checked.
- **Out of scope** — Changes to the AI instructions themselves, and to what the product returns.
- **Not built yet** — Customer Book golden checks. This rework removed the old checks and their golden answers, but kept Customer Book's source files for the rebuild in a later round. Until then Customer Book has no golden check.
- **Not built yet** — A score or percentage, a history of past results, and running several models in one go. None was asked for.

### Requirements across the epic

#### Functional

1. A **golden answer** is the correct AI answer, written down in advance, in the shape the AI returns it, not the shape of the final product response. It starts from one careful AI reading and is then corrected by hand against the source and the extraction rules, including the AI's notes. Running the check never changes a golden answer. *Before this rework, the golden notes were never hand-checked, because notes were not compared.*
2. All golden answers are made again for the new check. Each new golden answer comes from one live AI run, which happens only when the developer gives the go-ahead and supplies the key. *Before this rework, the old golden answers were used. They are now removed and replaced.*
3. The Catalogue check holds exactly these cases:
   - two price-list pictures, the first sent with the old check's exact processing message, kept word for word: "ignore cost, the items that are represented with 1 kg make them 12 kg per box, keep fruits keep them as it is, rest keep them as it is";
   - one PDF price list;
   - three spreadsheets;
   - one unrelated picture, one unrelated spreadsheet and one unrelated PDF, which must give no items;
   - one note-shortening case.

   *Before this rework, these were four separate checks, one each for pictures, PDF, spreadsheets and unrelated files.*
4. The note-shortening case reuses an existing Catalogue CSV spreadsheet. For that case only, the group size is lowered, so the spreadsheet is read in two or more groups. In that case only, the check runs its own copy of the product's join-and-shorten step: it always joins the groups' answers the same way the product does, and always shortens the notes with the product's own shortening instructions. The other spreadsheet cases are read in one group and are not joined or shortened. Unlike the product, the check has no fallback and does not judge the shortened notes: if shortening fails, the case fails, where the product would quietly keep the original notes, and an empty or longer shortened answer is compared as it is. The answer is compared with its golden answer like any other case. *Before this rework, a separate note-shortening check sent five fixed note lists straight to the AI. That check and its golden answers are now gone, for Catalogue and Customer Book alike.*
5. Every field of the AI's answer is compared. That covers:
   - the items and their variants;
   - the AI's entry counts: entries identified and entries processed;
   - the row numbers of empty and unreadable rows;
   - whether the AI assumed a selling unit or a currency;
   - the AI's notes, both the general notes and each item's notes.

   The product adds some details afterwards, so they are not compared here: the "Processed N of M entries" summary lines, the duplicate marks, and whether an item needs attention. A note worded differently from the golden answer is a difference. *Before this rework, notes were only counted and were not compared.*
6. Every list is compared position by position, and order counts: items, variants, attributes, notes and row numbers. Nothing is paired by product code (SKU) or name. The comparison first lines up entries that are exactly the same, then compares the rest by position ([gap 8](#8-one-skipped-or-extra-item-can-make-many-later-items-differ)). A field left out and the same field written as empty ("null") count as the same. *Before this rework, names also matched when they were at least 70% alike.*
7. Any difference fails the check. *Before this rework, a check passed whatever its score.*

#### Non-functional

1. The check also fails when something goes wrong: the AI cannot be reached or runs out of time, the answer cannot be read, or a golden answer is missing.
2. Results are shown on screen only and nothing is saved. To compare runs, the developer keeps the output they want.
3. The developer supplies their own AI key when they run the check. No key is ever stored in the repository. Without a key, the check is skipped, as before.

### User flow

This epic has a single step: a developer running the check.

1. [Run the check](#1-run-the-check)

### Prerequisites

Each case has a golden answer made and corrected by hand as described above. In the first picture, lemons, limes, oranges, grapefruit and avocado count as fruit and stay at 1 kg.

### 1. Run the check

**Who can reach this step: developers, running the check by hand with their own AI key.**

- The developer runs the check. They can choose the AI model through the same setting the product already uses. If they choose none, the product's default model is used.
- Each source is sent straight to the AI with the product's AI instructions and its processing message, if it has one. The AI's answer is compared with that source's golden answer.
- When a case fails, the developer reads the list of differences.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. The model returns exactly the golden answer | - No difference is shown, and the case passes |
| 2. The model misreads one price, for example 8.1 instead of 8.4 | - The difference shows both values, and the case fails |
| 3. The model skips one row, for example "Κραμπί" | - When the items around it match the golden answer exactly, it shows as one `remove` at its position - Otherwise later items can be compared with the wrong golden item and show as many changes ([gap 8](#8-one-skipped-or-extra-item-can-make-many-later-items-differ)) - The case fails either way |
| 4. The model returns an item the golden answer does not have | - An extra item at the end of the list shows as one `add` at `/candidates/-` (the end of the list) - Elsewhere, it is one `add` at its position when the items around it match exactly, otherwise many changes (gap 8) - The case fails either way |
| 5. The model adds a description the golden answer does not have | - It shows as a `replace` with `"old" : null`, and the case fails |
| 6. The model drops an accent, for example "Κραμπι" for "Κραμπί" | - The difference shows both texts, and the case fails. No closeness is shown. *Before this rework, the report also showed "1 character off, 83% alike".* |
| 7. Only one of the AI's notes is worded differently | - It shows as a single `replace` at that note's position, and the case fails. *Before this rework, notes did not affect the result.* |
| 8. The model returns the same items, notes or rows in a different order | - Each moved entry shows as an `add` plus a `remove`, and the case fails |
| 9. The model writes a name 90% alike to the golden name, with no product code | - It shows as a changed name (`replace`) on the item at that position, and the case fails. *Before this rework, they were paired as one item.* |
| 10. The model writes 8.40 where the golden answer has 8.4 | - It counts as the same, so no difference is shown, as before |
| 11. An unrelated picture, spreadsheet or PDF is sent | - The answer must match its golden answer, which holds no items but still has its counts, row lists and notes, or the case fails |
| 12. The note-shortening case runs | - The spreadsheet is read in groups, joined and its repeated notes shortened by the check's own copy of the product's step, and the result must match its golden answer, or the case fails |
| 13. A golden answer is missing | - The case stops before the AI is called, with a message naming the missing answer, and writes nothing |
| 14. The AI cannot be reached or runs out of time | - The case fails, as before |
| 15. No AI key is supplied | - The case is skipped, as before |

#### Requirements

1. A failing case prints only the comparison tool's own output: the differences, readable without colour, as indented blocks, one per difference, with longer values spread over several lines. The run already names the failing case, and the source and model names are not printed. Each block is one of:
   - `remove`: missing, only in the golden answer, so the AI left it out;
   - `add`: extra, only in the AI's answer;
   - `replace`: changed, with `old` the golden value and `value` the AI's.

   Each block says where the difference is, by position, counting from 0. For example, `/candidates/8/variants/0/extractionNotes/0` is the first note of the first variant of the ninth item. A whole missing or extra item is one block holding the whole item. A field filled on one side only shows as a `replace` with `"old" : null`. *Before this rework, the report had a summary naming the source and the model, a "% right" table, a note-count line and a grouped problem list.*
2. A missing golden answer is never created from the model's answer. A new golden answer is only ever written deliberately and corrected by hand.

#### Request / Response / Outcome

**Request**

Request is empty. This is a hand-run check, not a request to the product.

**Response**

Response is empty. The result is the on-screen difference output, and whether each case passes.

**Outcome**

- Any difference fails that case, and the developer sees a list of only the differences, readable without colour.
- Nothing is saved, and the golden answers are unchanged.

#### Http Error Responses

None. The check does not go through the product's web interface.

### Known gaps and open questions

#### 1. The second picture's row count is settled

When its new golden answer was corrected by hand, the second picture was counted row by row: it has 125 priced rows (29 + 45 + 51 across its three columns). It has no repeated strip of rows: the top of the image is blank padding.

Three of its product codes have one blurred digit. Their golden answer keeps the human reading (the user's choice). When the AI reads that digit differently, it usually shows as a changed code on that item.

**Settled:** nothing is left to decide.

#### 2. Customer Book has no golden check until it is rebuilt

This rework removed Customer Book's golden checks and their golden answers: ten contact-list pictures (one of them a second time with an instruction to leave out phone numbers), two PDFs, four spreadsheets, unrelated files, and five note-shortening cases. The source files stay, ready for the rebuild. Until they are rebuilt, a change to the Customer Book AI instructions cannot be checked against golden answers.

**To decide:** when to rebuild Customer Book's golden checks the same way as Catalogue's.

#### 3. Differences in note wording fail the check

Notes are now compared, and the check fails on any difference. The AI rarely words notes the same way twice, so a run can fail even when every item is right. The first live run confirmed it: the three unrelated files failed on note wording alone.

**Accepted for now:** this was the user's choice. Revisit it if note wording makes the check fail too often to be useful.

#### 4. A failing case prints only the differences

A failing case used to print the whole answer with the differences marked only in colour. A small difference was hard to find in a long answer, and without colour, missing and extra could not be told apart.

**Built:** a failing case now prints only the differences, readable without colour (step 1, requirement 1). Nothing is left to decide.

#### 5. The check's spreadsheet step is a copy of the product's

The check joins spreadsheet groups and shortens notes with its own copy of the product's step, so the product itself is untouched. A later product change to joining or shortening does not reach the check on its own. The check does not copy the product's rule for when shortening runs. The product keeps the original notes when shortening fails, comes back empty or gives more notes than it was sent; the check does not, so only the product's own automated tests prove that rule.

**Accepted for now:** this was the user's choice. A product change to joining or shortening must be copied into the check by hand.

#### 6. Characters that look the same can fail a case without a visible difference

Some letters and marks look identical but are different characters. Examples are the Greek and Latin capitals X/Χ, I/Ι and L/Λ, and straight and curly apostrophes. Where the source gives evidence, such as the PDF's own text for an apostrophe, the golden answer follows the source. Otherwise it keeps the AI's choice. If the AI picks the other character in a later run, the case fails, but both sides of the difference look the same. The first live run confirmed it: look-alike Latin and Greek letters showed up as differences in the second picture.

**Accepted for now:** this was the user's choice.

#### 7. At default settings the AI still breaks some reading rules

In the first live run, at the product's default settings, the AI broke some of the Catalogue reading rules:

- entry counts that do not add up;
- an unreadable row reported as a left-out line;
- a packaging unit dropped;
- kilogram units taken from a column header not marked as worked out from context, which the [Catalogue]({{ site.baseurl }}{% link epics/06-catalogue.md %}) extraction rules already require (its requirement 10).

The check shows these as differences, and the golden answers keep the rules.

**Accepted for now:** this was the user's choice. Any work on the AI instructions to fix these would be a separate request.

#### 8. One skipped or extra item can make many later items differ

The comparison first lines up list entries that are exactly the same, then compares what is left by position. A skipped or extra item shows as a single `remove` or `add` only when the items around it match the golden answer exactly. Otherwise each later item can be compared with the wrong golden item and print as many field changes, which hides the one real difference.

**Accepted for now:** this was the user's choice.

{% raw %}{% include abbreviations.md %}{% endraw %}
