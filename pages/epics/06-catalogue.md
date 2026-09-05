---
title: Catalogue
---

# Catalogue

### Overview

Every organization keeps a catalogue of what it sells — products, services, anything with a name and a price. It is where future orders will pick items from.

### Related / Out of scope

- **Related** — [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}). An organization must exist first, and every request here names which organization it is for.
- **Related** — [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}). The two work the same way — add, browse, open, update, archive — for a different kind of record. What is written once here is not repeated.
- **Not built yet** — Orders. The catalogue exists so that orders can pick items from it later, but nothing places or records an order yet.
- **Not built yet** — Search, filtering and paging. The list returns every active item in one go.

### Requirements across the epic

Every request in this epic names the organization it applies to, and only touches that organization's catalogue.

#### Functional

1. An item has a name, a unit it is sold by, and may have an exact price. A price is either fully given — an amount and a currency together — or left out entirely; there is no such thing as a price with only one of the two.
2. An item's name must be unique among that organization's active items. Archiving an item frees its name for reuse.
3. Items are **archived**, never deleted.
4. An item may carry one image. Uploading a new one replaces it.

#### Non-functional

1. Every request needs a signed-in person who has finished personal onboarding, and who belongs to the organization named in the request.
2. Reading is open to any member. Adding, changing, archiving and uploading an image are limited to owners and admins.
3. Money is never approximated. An amount is kept to the exact precision it was given, then padded out to the currency's standard number of decimal places — never rounded.
4. When a request contains several problems at once, all of them are reported together, and each is tied to the exact item that caused it.
5. Adding several items at once is all-or-nothing. If any one of them fails, none of them are stored.
6. A missing, invalid, or expired access token is refused with the same error code used everywhere else in the product for a rejected access token.

### User flow

Like the customer book, these are not a single journey. They are the things that can be done to a catalogue, and each stands on its own.

1. [User Adds an Item](#1-user-adds-an-item)
2. [User Uploads an Item's Image](#2-user-uploads-an-items-image)
3. [User Browses the Catalogue](#3-user-browses-the-catalogue)
4. [User Opens an Item](#4-user-opens-an-item)
5. [User Updates an Item](#5-user-updates-an-item)
6. [User Archives an Item](#6-user-archives-an-item)

### Prerequisites

**Which organization** — every request names the organization whose catalogue it is touching, and the person must belong to it.

**Roles** — reading is open to owners, admins and ordinary members. Adding, changing, archiving and uploading an image are limited to owners and admins, exactly as in the [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}#prerequisites).

**Item status** — an item is `Active` or `Archived`. New items start active. Archiving is one-way.

**Unit** — a free-form word for what the item is sold in: `piece`, `kg`, `hour`, or anything else the business needs. There is no fixed list.

**Price** — optional. When given, it is an exact amount plus a three-letter currency code. A currency without a fixed, standard number of decimal places is not accepted.

### 1. User Adds an Item

**Who can reach this step: an owner or admin of the organization.**

- User gives the item a name, a unit, and optionally an exact price.
- Several items can be added in one go.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User adds an item with no price | - Stored as active, with no price recorded |
| 2. User adds an item with a price | - The amount and currency are stored together - The amount is kept exact and padded to the currency's standard number of decimal places, never rounded |
| 3. User adds several items at once | - All are stored together or none are - A single failure anywhere rolls the whole batch back |
| 4. User adds an item whose name is already taken by an active item | - Rejected as a conflict, naming what clashed - Archiving the existing item first would free the name |
| 5. User gives a price in a currency with no fixed number of decimal places | - Rejected. A currency must have a standard, unchanging number of decimal places |
| 6. User gives more decimal places than the currency allows | - Rejected, naming the amount that does not fit |
| 7. User gives an amount with twelve or more integer digits | - Rejected as too large |
| 8. User submits several bad entries at once | - Every problem is reported together, each tied to the item that caused it |
| 9. A member with the ordinary user role tries to add an item | - Rejected. Reading is open to everyone; changing is not |

#### Requirements

1. A name and a unit are required. A price is optional.
2. A price is either fully given — amount and currency together — or left out; there is no partial price.
3. A currency must resolve to a real, standard currency with a fixed number of decimal places.
4. An amount must not be negative, must have fewer than twelve integer digits, and is padded to the currency's exact number of decimal places without ever being rounded.
5. A name must be unique among the organization's active items.
6. Adding several items at once succeeds completely or not at all.

#### Request / Response / Outcome

A price, wherever it appears in this epic, is always this shape:

**Price**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Amount | `BigDecimal` | Non-negative; fewer than 12 integer digits; decimal places no more than the currency allows | ✅ | The exact amount |
| Currency | `String` | A real ISO currency code with a fixed number of decimal places | ✅ | Which currency the amount is in |

**Request — adding one item**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Name | `String` | 1–255 characters, trimmed; unique among active items | ✅ | The item's name |
| Unit | `String` | 1–255 characters, trimmed | ✅ | What it is sold by, in the business's own words |
| Price | `Price` | See **Price** above | ❌ | Left out means the item has no set price |

**Request — adding several at once**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Items | `CatalogueItem[]` | Empty by default. Each entry is the shape above | ❌ | The items to add, all together or not at all |

**Response**

Response is empty. A successful add answers with nothing but a success status — no identifier for the item just created, the same shape as [gap 1 in the Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}#1-adding-a-customer-tells-you-nothing-about-what-was-added).

**Outcome**

- The item is stored as active, with its price if one was given.
- For a batch, every item is stored together, or none is.
- If the name is already taken nothing at all is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid, reported together with the item each belongs to |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - An active item already has that name |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 2. User Uploads an Item's Image

**Who can reach this step: an owner or admin of the organization.**

- User picks an image for an existing, active item.
- We check it really is an image, store it, and make a smaller standard copy.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. Owner uploads a valid image for an active item | - The file is confirmed to be a PNG, JPEG or WEBP by inspecting its contents - The original is stored as sent - A normalised copy is stored, bounded to 640×640 |
| 2. Owner uploads a second image later | - The new files replace the old ones |
| 3. User uploads an image for an item that is archived, or does not exist | - Rejected - Today this is reported as a server error — see [gap 1](#1-uploading-for-a-missing-or-archived-item-is-reported-as-a-server-error) |
| 4. User uploads a file that is not a supported image | - Rejected and nothing is stored - Today this is reported as a server error, the same as [gap 2 in Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#2-choosing-the-wrong-file-is-reported-as-a-server-error) |
| 5. User uploads a file larger than the limit | - Should be rejected cleanly - Today it is not: the same stall affects this upload as the organization logo upload — see [gap 6 in Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#6-an-oversized-upload-does-not-fail-it-stalls) |
| 6. A member with the ordinary user role tries to upload | - Rejected. Only owners and admins may change an item's image |
| 7. The organization id, item id, or file name is missing from the request | - Rejected as an invalid request |

#### Requirements

1. Only PNG, JPEG and WEBP files are accepted, decided by inspecting the file's contents rather than its name or declared type.
2. A file may be up to 20 MB.
3. An item may hold one image. Uploading again replaces both the original and the normalised copy.
4. Only an active item can receive an image.
5. Only an owner or an admin of the organization may upload an item's image.

#### Request / Response / Outcome

**Request**

The body is the raw image file. Three values travel in the request's headers instead of the body:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form, sent as the `X-Organization-ID` header | ✅ | Which organization the item belongs to |
| Catalogue Item ID | `UUID` | Canonical 36-character form, sent as the `X-Catalogue-Item-ID` header | ✅ | Which item the image is for |
| File Name | `String` | 1–255 characters, trimmed, sent as the `X-File-Name` header | ✅ | The original name of the file, kept alongside the stored image |
| Image | Binary | PNG, JPEG or WEBP; up to 20 MB | ✅ | The image itself, sent as the request body |

**Response**

Response is empty. A successful upload answers with nothing but a success status.

**Outcome**

- The file is stored exactly as uploaded, and a normalised copy bounded to 640×640 is stored beside it, replacing any image the item already had.
- The item's active or archived status is never changed by this step.
- On any refusal nothing is stored. Working files created while handling the upload are always cleaned up, including when the upload fails.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - The organization id header is missing - The item id header is missing - The file name header is missing |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow it |
| 500 | `INTERNAL_SERVER_ERROR` | - The item does not exist, belongs to another organization, or is archived - The file is not a supported image - Unexpected error |

### 3. User Browses the Catalogue

**Who can reach this step: any member of the organization.**

- User opens the catalogue and sees every active item.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens the catalogue | - Every active item in the organization is returned, each with its name, status, and a picture if it has one |
| 2. An item has no image | - It appears in the list with nothing in place of a picture |
| 3. The organization has archived items | - They do not appear, and there is no way to list them again — the same gap as [Customer Book gap 4]({{ site.baseurl }}{% link epics/05-customer-book.md %}#4-archiving-is-final-and-archived-customers-cannot-be-found-again) |
| 4. The organization has no items yet | - An empty list is returned |

#### Requirements

1. Any member may browse the catalogue, whatever their role.
2. The list contains only active items.
3. Each entry carries a name, its status, and a picture if the item has one — but not its unit or price. See [gap 2](#2-browsing-the-catalogue-does-not-show-the-price).

#### Request / Response / Outcome

**Request**

Request is empty apart from naming the organization.

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Items | `CatalogueItemSummary[]` | Every active item | ✅ | The catalogue |

**CatalogueItemSummary**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Identifies the item, used to open it |
| Name | `String` | — | ✅ | The item's name |
| Status | `CatalogueItemStatus` | `ACTIVE` or `ARCHIVED` | ✅ | Always `ACTIVE` here, since only active items are listed |
| Image URL | `String` | A working link, present only if the item has an image | ❌ | A picture of the item |

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person does not belong to the organization |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 4. User Opens an Item

**Who can reach this step: any member of the organization.**

- User picks an item from the catalogue to see its full details.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens an item that exists | - Its name, unit, price if any, and picture if any are returned |
| 2. User opens an item that has been archived | - Its details are still returned. Archiving hides an item from the list, not from a direct look-up |
| 3. User opens an item that does not exist | - Reported as a server error rather than "not found" — the same gap as [Customer Book gap 2]({{ site.baseurl }}{% link epics/05-customer-book.md %}#2-looking-up-a-customer-that-is-not-there-is-reported-as-a-server-error) |

#### Requirements

1. An item is looked up by identifier.
2. The full details include the unit and price, which the list in step 3 leaves out.
3. An archived item can still be opened directly.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form, in the address | ✅ | Which item to open |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Identifies the item |
| Name | `String` | — | ✅ | The item's name |
| Unit | `String` | — | ✅ | What it is sold by |
| Price | `Price` | See **Price** in step 1 | ❌ | Present only if the item has a price |
| Image URL | `String` | A working link | ❌ | Present only if the item has an image |

This answer does not say whether the item is archived. See [gap 3](#3-opening-an-item-does-not-say-whether-it-is-archived).

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person does not belong to the organization |
| 500 | `INTERNAL_SERVER_ERROR` | - No item with that identifier - Unexpected error |

### 5. User Updates an Item

**Who can reach this step: an owner or admin of the organization.**

- User changes an item's name, unit, or price.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User changes an active item's details | - The change is saved - Fields left out of the request are left as they were |
| 2. User renames an item to a name another active item already has | - Rejected as a conflict |
| 3. User changes an item that has been archived, or does not exist | - Nothing happens, and it is reported as successful — the same gap as [Customer Book gap 3]({{ site.baseurl }}{% link epics/05-customer-book.md %}#3-changes-to-an-archived-customer-are-silently-discarded) |
| 4. A member with the ordinary user role tries to make a change | - Rejected |

#### Requirements

1. Only active items can be changed.
2. A field left out of the request keeps its current value; there is no way to clear a price once set. See [gap 4](#4-a-price-can-be-changed-but-never-removed).
3. A rename must still leave the name unique among active items.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Which item to change |
| Name | `String` | 1–255 characters, trimmed; unique among active items | ❌ | Leave out to keep the current name |
| Unit | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep the current unit |
| Price | `Price` | See **Price** in step 1 | ❌ | Leave out to keep the current price. There is no way to send "no price" |

**Response**

Response is empty. A successful change answers with nothing but a success status — and so does a change that quietly did nothing.

**Outcome**

- For an active item the details are replaced as described and nothing else changes.
- For an archived or missing item nothing is stored, and the answer is the same as success.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - An active item already has that name |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 6. User Archives an Item

**Who can reach this step: an owner or admin of the organization.**

- User retires an item no longer for sale.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User archives an active item | - The item becomes archived and leaves the catalogue - Its name becomes free for a new active item to use - Its image, if any, is kept |
| 2. User archives an item that is already archived, or does not exist | - Nothing happens, and it is reported as successful |
| 3. User wants an archived item back | - Not possible. There is no way to reverse archiving — the same gap as [Customer Book gap 4]({{ site.baseurl }}{% link epics/05-customer-book.md %}#4-archiving-is-final-and-archived-customers-cannot-be-found-again) |

#### Requirements

1. An archived item is kept in full, including its image, and can still be opened directly by identifier.
2. Archiving frees the name for reuse by a new active item.
3. Archiving cannot be undone.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Which item to archive |

**Response**

Response is empty, whether the item was archived just now, was already archived, or never existed.

**Outcome**

- An active item becomes archived and leaves the catalogue. Its record and image are kept.
- Its name is released, so a new active item may take it.
- An already-archived or missing item is left exactly as it was.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### Known gaps and open questions

Everything above describes what the product does today. Nothing in this section exists yet; each one needs a product answer before it can be built.

#### 1. Uploading for a missing or archived item is reported as a server error

Naming an item that does not exist, belongs to another organization, or has been archived is refused as a server error rather than a plain "this item cannot receive an image".

Choosing an item that was just archived by a colleague, or a stale link to one that no longer exists, are both ordinary situations, not failures on our side.

**To decide:** whether this should be a plain validation-style refusal instead, naming what was wrong with the item.

#### 2. Browsing the catalogue does not show the price

The list in step 3 gives a name, a status, and a picture — never the unit or the price. Someone browsing the catalogue cannot see what anything costs without opening each item individually.

For a short list this is a click each; for a long one it makes the list close to useless for its most obvious purpose, comparing prices at a glance.

**To decide:** whether the price should be shown in the list, and if the concern is response size for a very large catalogue, whether that is better solved by adding price than by leaving it out.

#### 3. Opening an item does not say whether it is archived

An archived item answers a direct look-up exactly like an active one — same fields, same shape. Nothing in the response says which it is.

This is sharper here than in the Customer Book, because [step 5](#5-user-updates-an-item)'s silent no-op on an archived item is the same trap: the edit screen for an archived item looks perfectly normal and editable, with nothing warning that a save will quietly do nothing.

**To decide:** whether the answer should include the item's status, the way [browsing]({{ site.baseurl }}{% link epics/05-customer-book.md %}#2-user-browses-the-customer-book) already does for active-only lists.

#### 4. A price can be changed but never removed

Updating an item can set a price it never had, or replace one it already has — but there is no way to send "this item no longer has a price". Once set, a price can only ever be changed, never cleared.

**To decide:** whether removing a price should be possible, and if so how the request should say "clear this" as distinct from "leave this alone".

{% include abbreviations.md %}
