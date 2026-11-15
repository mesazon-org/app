---
title: AI Response Performance
---

# AI Response Performance

> **Status: built.** Every Catalogue and Customer Book extraction check, and both note-shortening checks, now report how close the AI (artificial intelligence) answer comes to its golden answer.

### Overview

When the catalogue reads items out of a price list, or the customer book reads customers out of a contact list, an AI model does the reading. This epic describes how the team measures how close the model's answer comes to a hand-checked correct answer. Measuring it shows whether a change to the AI instructions made the answers better or worse, and how different AI models compare on the same pictures.

The measurement is built once and serves every AI extraction: Catalogue and Customer Book, from pictures, PDFs and spreadsheets, including the checks that unrelated pictures and files give no results. It also serves the checks that shorten repeated notes.

This is a check that developers run by hand. Customers never see it, and it changes nothing in the product itself.

### Related / Out of scope

- **Related** — [Catalogue]({{ site.baseurl }}{% link epics/06-catalogue.md %}) and [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}). Their extraction from pictures, PDFs and spreadsheets is the AI answer being measured.
- **Out of scope** — Changes to the AI instructions themselves, and to what the product returns.
- **Not built yet** — Keeping a history of past results, and running several models in one go. Neither was asked for.

### Requirements across the epic

#### Functional

1. A **golden answer** is the correct answer, written down in advance: it starts from one careful AI reading and is then corrected by hand to match what the source actually prints and the extraction rules. Running the check never changes a golden answer.
2. Catalogue measures two price-list pictures (one sent with the instruction "items in 1 kg become 12 kg per box, keep fruits as they are"), one PDF price list and three spreadsheets. Customer Book measures ten contact-list pictures, one of them a second time with an instruction to leave out phone numbers, two PDFs and four spreadsheets. Both also measure unrelated pictures and files, which must give nothing, and five note-shortening checks each. There each golden note is paired with an identical or at least 70% alike note in the answer, in any order, and the number of notes is measured too.
3. The golden answers are used as they stand today. In the first picture, lemons, limes, oranges, grapefruit and avocado count as fruit and stay at 1 kg.
4. Each item in the model's answer is paired with the expected item it matches best, first by product code (SKU), then by name. Names pair when they are identical or at least 70% alike. Within a paired item, variants pair by product code, then by the closest label. An expected item with no partner counts as **missing**. A model item with no partner counts as **extra**. Every detail of a missing or extra item counts. The order of items does not matter.
5. Every detail of an item is measured, and every detail counts the same. That covers:
   - the name and description of the item;
   - each variant's code, label, description, price, currency, selling unit and attributes;
   - the marks saying a value was assumed, missing or duplicated;
   - whether the item needs attention.

   The summary lines, such as "Processed 54 of 54 entries", are measured too. A detail the model makes up that the golden answer does not have, such as an invented description, counts as extra. The model's free-text notes are not measured. They are only counted for information.
6. Text must match exactly to count as matched. For each text that does not match, the report shows how close it came, as a character count and a percentage, for example "1 character off, 83% alike". That way one missing accent reads differently from a wrong word.
7. Numbers must have exactly the expected value. 8.40 and 8.4 are the same value; 8.1 instead of 8.4 is a mismatch.

8. The report layout, the percentage right for each kind of detail, and the note counts belong to the shared measurement. Every extraction gets the same report. To use it, an extraction supplies only its own way of comparing its answer with its golden answer.

#### Non-functional

1. The check reports only. A low score never fails it. It still fails when something goes wrong: the AI cannot be reached or runs out of time, the answer cannot be read, or a golden answer is missing.
2. Results are shown on screen only and nothing is saved. To compare runs, the developer keeps the reports they want.
3. The developer supplies their own AI key when they run the check. No key is ever stored in the repository. Without a key, the check is skipped, as it is today.

### User flow

This epic has a single step: a developer running the check.

1. [Run the check](#1-run-the-check)

### Prerequisites

Each measured source has a golden answer corrected by hand.

### 1. Run the check

**Who can reach this step: developers, running the check by hand with their own AI key.**

- The developer runs the check. They can choose the AI model through the same setting the product already uses for its picture-reading model. If they choose none, the product's default model is used.
- Each picture is sent to the AI and the answer is compared with that picture's golden answer.
- The developer reads the report for each picture.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. The model returns exactly the expected items and details | - The score is 100% with no mismatches, and the check passes |
| 2. The model misreads one price, for example 8.1 instead of 8.4 | - One price mismatch is reported with both values; everything else still counts as matched |
| 3. The model skips one row, for example "Κραμπί" | - One missing item is reported, and each of its details counts as missing; the items after it still pair up and count as matched |
| 4. The model returns an item the golden answer does not have | - One extra item is reported, and each of its details counts as extra |
| 5. The model adds a description the golden answer does not have | - The description counts as extra |
| 6. The model drops an accent, for example "Κραμπι" for "Κραμπί" | - It is a mismatch, and the report shows how close it was, for example "1 character off, 83% alike" |
| 7. The model writes 8.40 where the golden answer has 8.4 | - It counts as matched |
| 8. Only the model's free-text notes differ | - The score is not affected; the note counts show the difference |
| 9. The developer chooses a different model | - That model is used, and the report says which model ran |
| 10. A golden answer is missing | - The check stops with a message naming the missing answer and writes nothing |
| 11. The AI cannot be reached or runs out of time | - The check fails, as today |
| 12. No AI key is supplied | - The check is skipped, as today |

#### Requirements

1. For each picture, the report starts with a summary. It names the picture and the model that ran, gives the overall percentage right with the number of details behind it, and says how many items were paired, missing and extra.
2. Next comes a table with one row for each kind of detail, in plain names. Each row shows how many details were right, wrong, missing and extra, and the percentage right. The percentage right is the right details divided by all the details counted (right, wrong, missing and extra), so made-up details lower it. The overall percentage uses the same rule.
3. Next comes one line of note counts, marked as not scored: general notes, items with notes, and total item notes. Each count has the golden answer's count beside it as a rough reference only, because the golden answers' notes were never checked by hand. The note text is not printed. In the note-shortening checks this line always shows zeros, because there the notes are the measured items.
4. The report ends with every problem, grouped by item. Each wrong detail names the expected value and the value the model gave, and how close a text came. A missing item and an extra item each take one line saying how many details they carry. Nothing is left out of the list.
5. The model's full answer is not printed. The note-shortening checks are the exception: they still print the notes sent and the notes returned.
6. A missing golden answer is never created from the model's answer. A new golden answer is only ever written deliberately, by hand.

#### Request / Response / Outcome

**Request**

Request is empty. This is a hand-run check, not a request to the product.

**Response**

Response is empty. The result is the on-screen report described above.

**Outcome**

- The developer can see how good the model's answer is, and exactly where it went wrong.
- Nothing is saved, and the golden answers are unchanged.

#### Http Error Responses

None. The check does not go through the product's web interface.

### Known gaps and open questions

#### 1. The golden answers were not checked against the pictures again

The golden answers are used as they stand, by user decision. In a first look, the second picture's main table seemed to have about 124 priced rows, while its golden answer has 125 product codes. The screenshot also repeats a few rows in a cropped strip at the top, which the golden answer appears to leave out. Any mistake in a golden answer will show up as a model mismatch.

**To decide:** whether, and when, to check the golden answers against the pictures again.

#### 2. The large Customer Book spreadsheet golden answer was checked by script, not row by row

The 347-row spreadsheet's golden answer was checked against the file by script (every email, phone number and name appears in the file, no stray punctuation) rather than by reading every row.

**To decide:** whether to read it row by row.

{% raw %}{% include abbreviations.md %}{% endraw %}
