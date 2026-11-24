---
title: AI Response Performance
---

# AI Response Performance

> **Status: built.** The Catalogue check runs its 10 cases, and the Customer Book check its 23 cases, against hand-corrected golden answers. Every list is compared position by position, so order counts. A difference never fails a case: each case prints its stored and current differences as the comparison tool's indented lists, each placed by position, readable without colour and with no test id line. The old scored checks are removed: every Catalogue and Customer Book extraction check, both note-shortening checks, the scoring and all old golden answers. Customer Book's source files were kept, and its check is rebuilt from them ([gap 2](#2-customer-books-golden-check-is-rebuilt)). Notes in italics starting *Before this rework* describe the removed checks.
>
> **First live run**, at the product's default settings: it took about 7 minutes, and every case got an answer, with no AI errors or timeouts. All 10 cases showed differences; at that time any difference failed a case. The golden answers were not changed to make them pass (see gaps 3, 6 and 7). That run used the earlier comparison, which paired items by product code or name and printed the whole answer. The next default-settings run, on 2026-11-24, used the position-by-position comparison and wrote the first stored patches.
>
> **Customer Book check, built (user decision):** built the same way as Catalogue's, with 23 cases, described in the Customer Book parts of requirements 1 and 3 to 6 and in gaps 2 and 6. Its golden answers are hand-corrected from one careful capture under the notes-change instructions. Its first run at the product's default settings was on 2026-11-24, with the user's go-ahead, and wrote its stored patches.
>
> **Notes change, built (user decision):** the product's AI instructions now put the general notes in a fixed order and give "left out" notes one wording ([Catalogue]({{ site.baseurl }}{% link epics/06-catalogue.md %}) requirement 12 and scenario 36, [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}) requirement 22 and scenario 34). The golden answers follow that change, as requirements 1 and 2 describe.
>
> **Stored patches, built (user decision):** each case keeps a stored patch, its list of differences from the golden answer, and prints it beside today's; a difference never fails a case (requirements 7 and 8). All 33 first stored patches were written by one run at the product's default settings on 2026-11-24, with the user's go-ahead, so every case now prints its Stored and Current blocks.
>
> **Reading-consistency round, built 2026-11-24 (user decision).** The product's AI instructions now aim to read the same file as alike as possible on every run: fixed note sentences, settled ambiguous rules and character-by-character copying ([Catalogue]({{ site.baseurl }}{% link epics/06-catalogue.md %}) step 10, [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}) step 8). The golden answers were corrected to the new rules: 262 corrections across 17 of them. One run at the product's default settings on 2026-11-24, with the user's go-ahead, then rewrote all 33 stored patches (requirement 8). All 33 cases ran, and the differences from the golden answers fell from 598 to 282, with 18 cases now showing none (13 before). The largest drops were Catalogue's second picture (162 to 50) and Customer Book's 347-row export (356 to 185). A few cases rose slightly: Catalogue's first picture and its PDF (8 to 12), and one Customer Book picture (0 to 2).
>
> **Reading-consistency round 2, built (user decision).** After the user's own runs still showed too much noise, Customer Book reading changed again ([Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}) step 8): a phone number that cannot be real is left out with one fixed note; country-code basis notes always say "Country code", use a longer fixed source list, and repeat on a candidate only when its basis differs from the shared note; unused details belonging to an entry in a picture or PDF get one `<Kind> were not used` note per kind; and a left-out line with no listed kind, such as each slogan or tagline line, gets its own note with no kind word, `Left out "<line>", <beside>`, as in Catalogue. Spreadsheets are now read with more AI reasoning so the State/Region rule is followed ([gap 10](#10-spreadsheet-reads-are-slower-and-cost-more)). Two runs at the product's default settings on 2026-11-24 passed all 33 cases, and the first rewrote the stored patches: differences from the golden answers fell from 282 to 232. Reading the same files twice gave identical answers in 21 of 33 cases. Most of the remaining run-to-run differences are in Customer Book's 347-row export and Catalogue's dense price-list photo ([gap 7](#7-at-default-settings-the-ai-still-breaks-some-reading-rules)).
>
> **Reading-consistency round 3, built (user decision).** Customer Book keeps a phone whose code and number can be real, keeps a number with no workable code with `Country code is missing`, and leaves out a code with no number with `Phone number is missing`; an address basis for a country code is noted on the customer in pictures and PDFs too; and the `<Kind> were not used` list becomes Websites, Comments, Callback reminders, Balances, Birthdays, Loyalty tiers and Customer types, replacing Roles. Catalogue turns columns that describe the item into attributes and gives the not-used note only to columns that do not. In pictures and PDFs, both features list left-out lines row by row, top to bottom, left before right.

### Overview

When the catalogue reads items out of a price list, or the customer book reads customers out of a contact list, an AI (artificial intelligence) model does the reading. This epic describes how the team checks the model's answer against a hand-checked correct answer. That check shows whether a change to the AI instructions made the answers better or worse, and how different AI models compare on the same sources.

The check covers Catalogue extraction from pictures, PDFs and spreadsheets, including unrelated files that must give no results. One spreadsheet case also proves that repeated notes are shortened when a spreadsheet is read in groups. A second check covers Customer Book extraction from pictures, PDFs and spreadsheets the same way. *Before this round, only Catalogue had a check (user decision to add Customer Book's).*

The check measures only the AI's own answer: what the AI returns before the product works on it. The product's own follow-up steps are fixed rules, not AI, and the product's existing automated tests already cover them. One example is finding duplicate names and product codes, then marking items that need attention. *Before this rework, the check ran the whole catalogue extraction and compared the final product response. Now it sends the source and the AI instructions straight to the AI, and compares the AI's answer.*

This is a check that developers run by hand. Customers never see it, and it changes nothing in the product itself.

### Related / Out of scope

- **Related** — [Catalogue]({{ site.baseurl }}{% link epics/06-catalogue.md %}) and [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}). Their extraction from pictures, PDFs and spreadsheets is the AI answer being checked.
- **Out of scope** — Changes to the AI instructions themselves, and to what the product returns.
- **Related** — Customer Book's golden check. This rework removed the old checks and their golden answers, but kept Customer Book's source files, and the check is rebuilt from them ([gap 2](#2-customer-books-golden-check-is-rebuilt)).
- **Not built yet** — A score or percentage, a history of past results, and running several models in one go. None was asked for.

### Requirements across the epic

#### Functional

1. A **golden answer** is the correct AI answer, written down in advance, in the shape the AI returns it, not the shape of the final product response. It starts from one careful AI reading and is then corrected by hand against the source and the extraction rules, including the AI's notes. Running the check never changes a golden answer. *Before this rework, the golden notes were never hand-checked, because notes were not compared.*

   Customer Book's golden answers are made and corrected the same way, from one new careful capture of all 23 cases taken after the notes change, only with the user's go-ahead. *A capture made before the notes change was not used (user decision).* The same correction rulings as Catalogue's apply (user decision):
   - for look-alike characters, the golden answer follows the source where there is evidence, otherwise the letters around it, as the product rule says ([gap 6](#6-characters-that-look-the-same-can-show-a-difference-that-looks-like-none)). *Before the reading-consistency round, without evidence it kept the AI's choice*;
   - a blurred character keeps the human reading;
   - a missing accent, or a Greek-style spelling of a foreign word, does not get the "may be misspelled in the source" note; only a clear spelling mistake does.
2. All golden answers are made again for the new check. Each new golden answer comes from one live AI run, which happens only when the developer gives the go-ahead and supplies the key. *Before this rework, the old golden answers were used. They are now removed and replaced.*

   With the notes change, only the general notes of the committed Catalogue golden answers were edited by hand to the new wording and order, in the 7 of the 10 where they differed, with no new capture; nothing else in them changed. *Before the notes change, the committed golden answers stayed exactly as committed (user decision to edit their general notes once).*
3. The Catalogue check holds exactly these cases:
   - two price-list pictures, the first sent with the old check's exact processing message, kept word for word: "ignore cost, the items that are represented with 1 kg make them 12 kg per box, keep fruits keep them as it is, rest keep them as it is";
   - one PDF price list;
   - three spreadsheets;
   - one unrelated picture, one unrelated spreadsheet and one unrelated PDF, which must give no items;
   - one note-shortening case.

   *Before this rework, these were four separate checks, one each for pictures, PDF, spreadsheets and unrelated files.*

   The Customer Book check reuses the kept source files and holds exactly these 23 cases:
   - ten contact-list pictures;
   - the first of those pictures sent a second time with this processing message, word for word: "omit phone numbers, keep everything else";
   - two PDFs, one typed and one scanned from handwriting;
   - four spreadsheets, one of them a 347-row contact export;
   - four unrelated pictures, one unrelated PDF and one unrelated spreadsheet, which must give no customers.

   *Before this round, Customer Book had no check (user decision to add these cases).*
4. The note-shortening case reuses an existing Catalogue CSV spreadsheet. For that case only, the group size is lowered, so the spreadsheet is read in two or more groups. In that case only, the check runs its own copy of the product's join-and-shorten step: it always joins the groups' answers the same way the product does, and always shortens the notes with the product's own shortening instructions. The other spreadsheet cases are read in one group and are not joined or shortened. Unlike the product, the check has no fallback and does not judge the shortened notes: if shortening fails, the case fails, where the product would quietly keep the original notes, and an empty or longer shortened answer is compared as it is. The answer is compared with its golden answer like any other case. *Before this rework, a separate note-shortening check sent five fixed note lists straight to the AI. That check and its golden answers are now gone, for Catalogue and Customer Book alike.*

   For Customer Book, the 347-row export is the note-shortening case, at the normal group size, so it is read in several groups. It is joined and always shortened with the check's own copy of the product's step and Customer Book's own shortening instructions, with the same rules as above: no fallback, and the shortened notes compared as they are. The other Customer Book spreadsheets are read in one group and are not joined or shortened. *Before this round, Customer Book had no such case (user decision).*

   The general notes come in the same order whether a spreadsheet is read in one group or several: shortening re-sorts them into the order of kinds and merges repeats, and with fixed sentences, repeats read identically.
5. Every field of the AI's answer is compared. That covers:
   - the items and their variants;
   - the AI's entry counts: entries identified and entries processed;
   - the row numbers of empty and unreadable rows;
   - whether the AI assumed a selling unit or a currency;
   - the AI's notes, both the general notes and each item's notes.

   The product adds some details afterwards, so they are not compared here: the "Processed N of M entries" summary lines, the duplicate marks, and whether an item needs attention. A note worded differently from the golden answer is a difference. *Before this rework, notes were only counted and were not compared.*

   For Customer Book, every field of the AI's answer is compared too:
   - the people and businesses found, with their names, emails, phone numbers and country codes, addresses with their type, and a business's tax ID;
   - a business's contacts, with their name, role, email and phone;
   - the entry counts and the row numbers of empty and unreadable rows;
   - whether the AI worked out a name or a phone country code;
   - all of the AI's notes.

   The product's follow-up rules are not compared, because they come after the AI's answer: the duplicate and missing marks, whether a customer needs attention, which email and phone become the default, the limit of two addresses with their retyping and "left out" notes, and the summary lines. *Before this round, none of this was compared, because there was no check (user decision).*
6. Every list is compared position by position, and order counts: items, variants, attributes, notes and row numbers, and for Customer Book also people, businesses, contacts, emails, phone numbers and addresses. Nothing is paired by product code (SKU) or name. The comparison first lines up entries that are exactly the same, then compares the rest by position ([gap 8](#8-one-skipped-or-extra-item-can-make-many-later-items-differ)). A field left out and the same field written as empty ("null") count as the same. *Before this rework, names also matched when they were at least 70% alike.*
7. A difference never fails a case: each case prints its stored and current differences, and the developer judges by eye whether the AI got better or worse ([gap 9](#9-a-difference-never-turns-the-check-red)). *Before stored patches, any difference failed the case (user decision).* *Before this rework, a check passed whatever its score.*
8. Each case of both checks keeps a **stored patch**: its list of differences from the golden answer, from an accepted run.
   - A run writes a case's stored patch only when it has none, and only at the product's default settings. A run at any other model or setting prints only the current differences, with a line saying nothing was stored because the settings are not the defaults.
   - A run never overwrites a stored patch. To refresh one, the developer deletes it on purpose and runs again.
   - Stored patches are committed and reviewed like golden answers. They come from one run at the product's default settings, with the user's go-ahead and key.
   - Editing a golden answer means deleting its stored patch and rewriting it in the same change.
   - The first stored patches are made once each check's golden answers are final, which they now are after the notes change. They were written by one default-settings run on 2026-11-24 (see the status above).

   *Before stored patches, there were none (user decision).*

#### Non-functional

1. The check also fails when something goes wrong: the AI cannot be reached or runs out of time, the answer cannot be read, a golden answer is missing, or note shortening fails. These still fail as before, although a difference never does.
2. Results are shown on screen. A run saves only a missing stored patch (requirement 8), and nothing else. *Before stored patches, nothing was saved, and to compare runs the developer kept the output they wanted (user decision).*
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
- After each case, the developer compares its Stored and Current blocks and judges whether the AI got better or worse.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. The model returns exactly the golden answer | - The Current block is empty, and the case passes |
| 2. The model misreads one price, for example 8.1 instead of 8.4 | - The Current block shows both values, and the case passes |
| 3. The model skips one row, for example "Κραμπί" | - When the items around it match the golden answer exactly, it shows as one `remove` at its position - Otherwise later items can be compared with the wrong golden item and show as many changes ([gap 8](#8-one-skipped-or-extra-item-can-make-many-later-items-differ)) - Either way it is in the Current block, and the case passes |
| 4. The model returns an item the golden answer does not have | - An extra item at the end of the list shows as one `add` at `/candidates/-` (the end of the list) - Elsewhere, it is one `add` at its position when the items around it match exactly, otherwise many changes (gap 8) - Either way it is in the Current block, and the case passes |
| 5. The model adds a description the golden answer does not have | - It shows as a `replace` with `"old" : null` in the Current block, and the case passes |
| 6. The model drops an accent, for example "Κραμπι" for "Κραμπί" | - The Current block shows both texts, and the case passes. No closeness is shown. *Before this rework, the report also showed "1 character off, 83% alike".* |
| 7. Only one of the AI's notes is worded differently | - It shows as a single `replace` at that note's position in the Current block, and the case passes. *Before this rework, notes did not affect the result.* |
| 8. The model returns the same items, notes or rows in a different order | - Each moved entry shows as an `add` plus a `remove` in the Current block, and the case passes |
| 9. The model writes a name 90% alike to the golden name, with no product code | - It shows as a changed name (`replace`) on the item at that position in the Current block, and the case passes. *Before this rework, they were paired as one item.* |
| 10. The model writes 8.40 where the golden answer has 8.4 | - It counts as the same, so no difference is shown, as before |
| 11. An unrelated picture, spreadsheet or PDF is sent | - The answer is compared with its golden answer, which holds no items or customers but still has its counts, row lists and notes - Any difference shows in the Current block |
| 12. The note-shortening case runs | - The spreadsheet is read in groups, joined and its repeated notes shortened by the check's own copy of the product's step, and the result is compared with its golden answer - Any difference shows in the Current block |
| 13. A golden answer is missing | - The case stops before the AI is called, with a message naming the missing answer, and writes nothing |
| 14. The AI cannot be reached or runs out of time | - The case fails, as before |
| 15. No AI key is supplied | - The case is skipped, as before |
| 16. A case has no stored patch, and the run uses the product's default settings | - The run writes the stored patch - It prints the Current block, with a line saying the patch was stored - The case passes |
| 17. A case has a stored patch | - The run prints the Stored block, then the Current block - It writes nothing, and the case passes even when the two differ |
| 18. A case has no stored patch, and the run uses another model or setting | - The run prints only the Current block, with a line saying nothing was stored because the settings are not the defaults - It writes nothing |
| 19. A golden answer is edited | - Its stored patch is deleted and rewritten, from a default-settings run, in the same change |

#### Requirements

1. Each case prints two labelled blocks, "Stored" then "Current", with no score and no count; a case with no stored patch prints only "Current" (scenarios 16 and 18). Each block is the comparison tool's own output: the differences, readable without colour, as indented blocks, one per difference, with longer values spread over several lines. *Before stored patches, only a failing case printed, and it printed one list (user decision).* The run already names each case, and that name includes its source file. The differences themselves name neither the source nor the model. Each block is one of:
   - `remove`: missing, only in the golden answer, so the AI left it out;
   - `add`: extra, only in the AI's answer;
   - `replace`: changed, with `old` the golden value and `value` the AI's.

   Each block says where the difference is, by position, counting from 0. For example, `/candidates/8/variants/0/extractionNotes/0` is the first note of the first variant of the ninth item. A whole missing or extra item is one block holding the whole item. A field filled on one side only shows as a `replace` with `"old" : null`. *Before this rework, the report had a summary naming the source and the model, a "% right" table, a note-count line and a grouped problem list.*
2. A missing golden answer is never created from the model's answer. A new golden answer is only ever written deliberately and corrected by hand. A stored patch is never written over (requirement 8).

#### Request / Response / Outcome

**Request**

Request is empty. This is a hand-run check, not a request to the product.

**Response**

Response is empty. The result is each case's printed Stored and Current blocks.

**Outcome**

- The developer sees each case's stored and current differences, readable without colour, and judges whether the AI got better or worse.
- Only a missing stored patch is saved, by a default-settings run, and the golden answers are unchanged. *Before stored patches, any difference failed the case and nothing was saved (user decision).*

#### Http Error Responses

None. The check does not go through the product's web interface.

### Known gaps and open questions

#### 1. The second picture's row count is settled

When its new golden answer was corrected by hand, the second picture was counted row by row: it has 125 priced rows (29 + 45 + 51 across its three columns). It has no repeated strip of rows: the top of the image is blank padding.

Three of its product codes have one blurred digit. Their golden answer keeps the human reading (the user's choice). When the AI reads that digit differently, it usually shows as a changed code on that item.

**Settled:** nothing is left to decide.

#### 2. Customer Book's golden check is rebuilt

This rework removed Customer Book's golden checks and their golden answers: ten contact-list pictures (one of them in a second case meant to leave out phone numbers, which by then no longer sent that message), two PDFs, four spreadsheets, unrelated files, and five note-shortening cases. The source files were kept for the rebuild. Until it was rebuilt, a change to the Customer Book AI instructions could not be checked against golden answers.

**Built:** the user chose to rebuild it the same way as Catalogue's, and it now runs its 23 cases against hand-corrected golden answers (requirements 1 and 3 to 6). It was first run at the product's default settings on 2026-11-24. Nothing is left to decide.

#### 3. Differences in note wording fill the patch

Notes are now compared, and every difference shows in the Current block. The AI rarely words notes the same way twice, so a case can show differences even when every item is right. *Before stored patches, such a case failed (user decision).* The first live run confirmed it: the three unrelated files failed on note wording alone. The agreed notes change (a fixed order of general notes and one "left out" wording) is meant to make general-note differences rarer; it does not change how the AI words item, variant or customer notes. The reading-consistency round added fixed sentences for many of those notes too; other observations stay in the AI's own words.

**Accepted for now:** this was the user's choice. Revisit it if note wording makes the differences too long to be useful.

#### 4. A case prints only the differences

A case used to print the whole answer with the differences marked only in colour. A small difference was hard to find in a long answer, and without colour, missing and extra could not be told apart.

**Built:** a case now prints only the differences, readable without colour (step 1, requirement 1). Nothing is left to decide.

#### 5. The check's spreadsheet step is a copy of the product's

The check joins spreadsheet groups and shortens notes with its own copy of the product's step, so the product itself is untouched. A later product change to joining or shortening does not reach the check on its own. The check does not copy the product's rule for when shortening runs. The product keeps the original notes when shortening fails, comes back empty or gives more notes than it was sent; the check does not, so only the product's own automated tests prove that rule.

**Accepted for now:** this was the user's choice. A product change to joining or shortening must be copied into the check by hand.

#### 6. Characters that look the same can show a difference that looks like none

Some letters and marks look identical but are different characters. Examples are the Greek and Latin capitals X/Χ, I/Ι and L/Λ, and straight and curly apostrophes. Where the source gives evidence, such as the PDF's own text for an apostrophe, the golden answer follows the source. Otherwise it follows the letters around it, as the product rule says. *Before the reading-consistency round, it kept the AI's choice.* If the AI picks the other character in a later run, it shows as a difference whose two sides look the same. *Before stored patches, that difference also failed the case.* The first live run confirmed it: look-alike Latin and Greek letters showed up as differences in the second picture. The same ruling applies to Customer Book's golden answers (user decision), where names, emails and phone numbers raise the same choices.

**Accepted for now:** this was the user's choice.

#### 7. At default settings the AI still breaks some reading rules

In the first live run, at the product's default settings, the AI broke some of the Catalogue reading rules:

- entry counts that do not add up;
- an unreadable row reported as a left-out line;
- a packaging unit dropped;
- kilogram units taken from a column header not marked as worked out from context, which the [Catalogue]({{ site.baseurl }}{% link epics/06-catalogue.md %}) extraction rules already require (its requirement 10).

The check shows these as differences, and the golden answers keep the rules.

In the reading-consistency round 2 runs, the AI still sometimes took the city from a State or Region column in Customer Book's 347-row export, was inconsistent about whether to write a customer's own country-code note, and re-read names and digits differently in Catalogue's dense price-list photo.

**Accepted for now:** this was the user's choice. Any work on the AI instructions to fix these would be a separate request.

#### 8. One skipped or extra item can make many later items differ

The comparison first lines up list entries that are exactly the same, then compares what is left by position. A skipped or extra item shows as a single `remove` or `add` only when the items around it match the golden answer exactly. Otherwise each later item can be compared with the wrong golden item and print as many field changes, which hides the one real difference.

**Accepted for now:** this was the user's choice.

#### 9. A difference never turns the check red

A case never fails on a difference: it prints its Stored and Current blocks and passes. A degradation is seen only by someone who reads and compares the two blocks; nothing in the result itself shows it.

**Accepted for now:** this was the user's choice.

#### 10. Spreadsheet reads are slower and cost more

To make the AI follow the rule that a State or Region column is never the city, spreadsheets are now read with more AI reasoning (medium instead of low), in the product as well as in the check. Every spreadsheet read therefore takes longer and costs more. *Before this round, spreadsheets were read with low reasoning.*

**Accepted for now:** this was the user's choice.

{% raw %}{% include abbreviations.md %}{% endraw %}
