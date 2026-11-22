---
title: AI Response Performance
---

# AI Response Performance

> **Status: rework agreed, not built yet.** Today every Catalogue and Customer Book extraction check, and both note-shortening checks, print a scored report with percentages and never fail on the score. After this rework, only Catalogue has golden checks. They live in one check, print only the differences from the golden answer, and fail on any difference. Customer Book's golden checks and both note-shortening checks are removed. Customer Book's checks will be rebuilt in a later round.

### Overview

When the catalogue reads items out of a price list, an AI (artificial intelligence) model does the reading. This epic describes how the team checks the model's answer against a hand-checked correct answer. That check shows whether a change to the AI instructions made the answers better or worse, and how different AI models compare on the same sources.

After this rework, the check covers Catalogue extraction from pictures, PDFs and spreadsheets, including unrelated files that must give no results. One spreadsheet case also proves that repeated notes are shortened when a spreadsheet is read in groups.

The check measures only the AI's own answer: what the AI returns before the product works on it. The product's own follow-up steps are fixed rules, not AI, and the product's existing automated tests already cover them. One example is finding duplicate names and product codes, then marking items that need attention. *Today the check runs the whole catalogue extraction and compares the final product response. After this, it sends the source and the AI instructions straight to the AI, and compares the AI's answer.*

This is a check that developers run by hand. Customers never see it, and it changes nothing in the product itself.

### Related / Out of scope

- **Related** — [Catalogue]({{ site.baseurl }}{% link epics/06-catalogue.md %}) and [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}). Their extraction from pictures, PDFs and spreadsheets is the AI answer being checked.
- **Out of scope** — Changes to the AI instructions themselves, and to what the product returns.
- **Not built yet** — Customer Book golden checks. Today they exist. This rework removes them, and they will be rebuilt in a later round. Until then Customer Book has no golden check.
- **Not built yet** — A score or percentage, a history of past results, and running several models in one go. None was asked for.

### Requirements across the epic

#### Functional

1. A **golden answer** is the correct AI answer, written down in advance, in the shape the AI returns it, not the shape of the final product response. It starts from one careful AI reading and is then corrected by hand against the source and the extraction rules, including the AI's notes. Running the check never changes a golden answer. *Today the golden notes were never hand-checked, because notes were not compared. After this, they are hand-checked.*
2. All golden answers are made again for the new check. *Today the existing golden answers are used. After this, they are removed and replaced.* Each new golden answer comes from one live AI run, which happens only when the developer gives the go-ahead and supplies the key.
3. The Catalogue check holds exactly these cases:
   - two price-list pictures, the first sent with the instruction "items in 1 kg become 12 kg per box, keep fruits as they are";
   - one PDF price list;
   - three spreadsheets;
   - one unrelated picture, one unrelated spreadsheet and one unrelated PDF, which must give nothing;
   - one note-shortening case.

   *Today these are four separate checks, one each for pictures, PDF, spreadsheets and unrelated files. After this, they are one check.*
4. The note-shortening case reuses an existing Catalogue CSV spreadsheet. For that case only, the group size is lowered. The check then does what the product does: it reads the spreadsheet in two or more groups and shortens the repeated notes. The answer is compared with its golden answer like any other case. *Today a separate note-shortening check sends five fixed note lists straight to the AI. After this, that check and its golden answers are gone, for Catalogue and Customer Book alike.*
5. Every field of the AI's answer is compared, the AI's notes included: the general notes and each item's notes. Details the product adds afterwards, such as duplicate marks and whether an item needs attention, are not part of the AI's answer, so they are not compared here. *Today notes are only counted and are not compared. After this, a note worded differently from the golden answer is a difference.*
6. The order of items does not matter. An item in the answer is matched with the expected item that shares a product code (SKU), or has exactly the same name when it has no code. Inside a matched item, variants are matched by product code, then by label. *Today names also match when they are at least 70% alike. After this, names must be identical.* An expected item with no partner is missing; an answer item with no partner is extra.
7. Any difference fails the check. *Today a check passes whatever its score.*

#### Non-functional

1. The check also fails when something goes wrong: the AI cannot be reached or runs out of time, the answer cannot be read, or a golden answer is missing.
2. Results are shown on screen only and nothing is saved. To compare runs, the developer keeps the output they want.
3. The developer supplies their own AI key when they run the check. No key is ever stored in the repository. Without a key, the check is skipped, as it is today.

### User flow

This epic has a single step: a developer running the check.

1. [Run the check](#1-run-the-check)

### Prerequisites

Each case has a golden answer made and corrected by hand as described above. In the first picture, lemons, limes, oranges, grapefruit and avocado count as fruit and stay at 1 kg.

### 1. Run the check

**Who can reach this step: developers, running the check by hand with their own AI key.**

- The developer runs the check. They can choose the AI model through the same setting the product already uses. If they choose none, the product's default model is used.
- Each source is sent straight to the AI with the product's AI instructions and its processing message, if it has one. The AI's answer is compared with that source's golden answer.
- The developer reads the differences, if there are any.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. The model returns exactly the golden answer | - No difference is shown, and the case passes |
| 2. The model misreads one price, for example 8.1 instead of 8.4 | - The difference shows both values, and the case fails |
| 3. The model skips one row, for example "Κραμπί" | - That item shows as missing, the items after it still match, and the case fails |
| 4. The model returns an item the golden answer does not have | - That item shows as extra, and the case fails |
| 5. The model adds a description the golden answer does not have | - The difference is shown, and the case fails |
| 6. The model drops an accent, for example "Κραμπι" for "Κραμπί" | - The difference shows both texts, and the case fails. *Today the report also showed "1 character off, 83% alike". After this, no closeness is shown.* |
| 7. Only the AI's notes are worded differently | - The difference is shown, and the case fails. *Today notes did not affect the result.* |
| 8. The model returns the same items in a different order | - No difference is shown for the order |
| 9. The model writes a name 90% alike to the golden name, with no product code | - The expected item shows as missing and the model's item as extra. *Today they were paired as one item.* |
| 10. The model writes 8.40 where the golden answer has 8.4 | - *Today this counts as matched. After this, there is no special rule. The comparison decides it as it stands, so it may show as a difference.* |
| 11. An unrelated picture, spreadsheet or PDF is sent | - The answer must match its golden answer, which holds nothing, or the case fails |
| 12. The note-shortening case runs | - The spreadsheet is read in groups and its repeated notes are shortened, as the product does, and the result must match its golden answer, or the case fails |
| 13. A golden answer is missing | - The case stops before the AI is called, with a message naming the missing answer, and writes nothing |
| 14. The AI cannot be reached or runs out of time | - The case fails, as today |
| 15. No AI key is supplied | - The case is skipped, as today |

#### Requirements

1. The output is only the list of differences between the golden answer and the model's answer, covering every field. *Today the report has a summary naming the source and the model, a "% right" table, a note-count line and a grouped problem list. After this, none of these is printed, the source and model names included.*
2. A missing golden answer is never created from the model's answer. A new golden answer is only ever written deliberately and corrected by hand.

#### Request / Response / Outcome

**Request**

Request is empty. This is a hand-run check, not a request to the product.

**Response**

Response is empty. The result is the on-screen difference output, and whether each case passes.

**Outcome**

- The developer sees exactly where the model's answer differs from the golden answer. Any difference fails that case.
- Nothing is saved, and the golden answers are unchanged.

#### Http Error Responses

None. The check does not go through the product's web interface.

### Known gaps and open questions

#### 1. The second picture's golden answer may have the wrong row count

In a first look, the second picture's main table seemed to have about 124 priced rows, while today's golden answer has 125 product codes. The screenshot also repeats a few rows in a cropped strip at the top, which that golden answer appears to leave out. Any mistake in a golden answer will show up as a model difference.

**To decide:** when the new golden answer for the second picture is corrected by hand, whether to settle its row count against the picture.

#### 2. Customer Book has no golden check until it is rebuilt

This rework removes Customer Book's golden checks: ten contact-list pictures (one of them a second time with an instruction to leave out phone numbers), two PDFs, four spreadsheets, unrelated files, and five note-shortening cases. Until they are rebuilt, a change to the Customer Book AI instructions cannot be checked against golden answers.

**To decide:** when to rebuild Customer Book's golden checks the same way as Catalogue's.

#### 3. Differences in note wording fail the check

Notes are now compared, and the check fails on any difference. The AI rarely words notes the same way twice, so a run can fail even when every item is right.

**Accepted for now:** this was the user's choice. Revisit it if note wording makes the check fail too often to be useful.

{% raw %}{% include abbreviations.md %}{% endraw %}
