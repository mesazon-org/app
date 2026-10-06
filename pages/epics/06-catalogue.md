---
title: Catalogue
---

# Catalogue

### Overview

> **Not built yet:** the variant behaviour in this epic is agreed but not implemented. Until it ships, items keep one unit and one price as described in the previous version of this epic.

Every organization keeps a catalogue of what it sells — products, services, anything it puts a name on. Each item comes in one or more variants: the actual things sold, each with its own unit, measure, packaging and price. It is where future orders will pick items from.

### Related / Out of scope

- **Related** — [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}). An organization must exist first, and every request here names which organization it is for.
- **Related** — [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}). The two work the same way — add, browse, open, update, archive — for a different kind of record. What is written once here is not repeated.
- **Not built yet** — Orders. The catalogue exists so that orders can pick items from it later, but nothing places or records an order yet.
- **Not built yet** — Search, filtering and paging. The list returns every active item in one go.

### Requirements across the epic

Every request in this epic names the organization it applies to, and only touches that organization's catalogue.

#### Functional

1. An item has a name and may have a description and an image. Everything that is sold — the unit, measure, packaging and price — lives on the item's **variants**, not on the item itself.
2. An item always has at least one active variant while it is active. It is created together with its first variants, and its last active variant cannot be archived on its own.
3. A variant has a unit it is sold by, and may have a name, a stock-keeping unit (SKU) code, a measure, a package type, a package count, and an exact price. A price is either fully given — an amount and a currency together — or left out entirely; there is no price with only one of the two. The same holds for a measure — a measure value and a measure unit together — and for packaging — a package type and a package count together. Every other optional detail stands on its own.
4. An item's name must be unique among that organization's active items. Archiving an item frees its name for reuse.
5. A variant's name must be unique among the active variants of the same item. A variant's SKU must be unique among all of the organization's active variants. Archiving a variant frees both.
6. Items and variants are **archived**, never deleted. Archiving an item archives all of its active variants with it.
7. An item may carry one image. Uploading a new one replaces it. Variants have no image of their own.

#### Non-functional

1. Every request needs a signed-in person who has finished personal onboarding and belongs to the organization named in the request. Someone outside the organization is refused with a server error — the same gap as [Organization Onboarding gap 3]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#3-not-belonging-to-an-organization-is-reported-as-a-server-error).
2. Reading is open to owners, admins and ordinary members. Adding, changing, archiving and uploading an image are limited to owners and admins. This holds for items and variants alike.
3. Prices and measures are exact. An amount is never rounded; it is kept with exactly the currency's standard number of decimal places. A measure value is kept exactly as given, with at most 12 digits before the decimal point and 6 after.
4. When a request contains several problems at once, all of them are reported together, each tied to the exact item or variant that caused it.
5. Adding an item with its variants, or several items at once, is all-or-nothing. If any one item or variant fails, nothing is stored.
6. A missing, invalid, or expired access token is refused with the same error code used everywhere else in the product for a rejected access token.

### User flow

Like the customer book, these are not a single journey. They are the things that can be done to a catalogue, and each stands on its own.

1. [User Adds an Item](#1-user-adds-an-item)
2. [User Uploads an Item's Image](#2-user-uploads-an-items-image)
3. [User Browses the Catalogue](#3-user-browses-the-catalogue)
4. [User Opens an Item](#4-user-opens-an-item)
5. [User Updates an Item](#5-user-updates-an-item)
6. [User Archives an Item](#6-user-archives-an-item)
7. [User Adds a Variant](#7-user-adds-a-variant)
8. [User Opens a Variant](#8-user-opens-a-variant)
9. [User Updates a Variant](#9-user-updates-a-variant)
10. [User Archives a Variant](#10-user-archives-a-variant)

### Prerequisites

**Item and variant status** — an item or variant is `Active` or `Archived`. New ones start active. Archiving is one-way.

**Variant** — one way an item is sold. A coffee item might have a "250 g bag" variant and a "1 kg bag" variant, each with its own price and SKU.

**Sale unit** — a free-form word for what a variant is sold in: `piece`, `box`, `hour`, or anything else the business needs. There is no fixed list. The same goes for the **measure unit** (`kg`, `ml`, ...) and the **package type** (`bottle`, `crate`, ...).

**Price** — optional. When given, it is an exact amount plus a three-letter currency code, following the rules in **Price** in [step 1](#1-user-adds-an-item).

### 1. User Adds an Item

**Who can reach this step: an owner or admin of the organization.**

- User gives the item a name, optionally a description, and at least one variant.
- Several items can be added in one go.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User adds an item with one variant and no price | - The item and its variant are stored as active, with no price recorded |
| 2. User adds an item whose variants have prices | - Each variant's amount and currency are stored together, exactly, never rounded |
| 3. User adds an item with several variants | - The item and every variant are stored together, or none are |
| 4. User adds an item with no variants | - Rejected and nothing is stored |
| 5. User adds several items at once | - All items and all their variants are stored together, or none are |
| 6. User adds an item whose name is already taken by an active item | - Rejected as a conflict, naming what clashed |
| 7. User gives a variant a SKU already used by an active variant in the organization, or gives two variants in the same request the same SKU | - Rejected as a conflict, naming the SKU |
| 8. User gives two variants of the same item the same name | - Rejected as a conflict |
| 9. User gives a price that breaks a rule in **Price** below — an unsupported currency, more decimal places than the currency allows, or an amount that is negative or too large | - Rejected, naming the part of the price at fault |
| 10. User gives a measure value of zero or less, a measure value with more than 12 digits before the decimal point or more than 6 after it, or a package count below 1 | - Rejected, naming the field at fault |
| 11. User gives a measure value without a measure unit, or the other way round; or a package type without a package count, or the other way round | - Rejected, naming the field left out |
| 12. User submits several bad entries at once | - Every problem is reported together, each tied to the item or variant that caused it |
| 13. A member with the ordinary user role tries to add an item | - Rejected |

#### Requirements

1. A name and at least one variant are required. A description is optional.
2. Each variant needs a sale unit. Its name, SKU and price are each optional on their own. Its measure and its packaging are optional as pairs: the measure value and measure unit are given together or left out together, and so are the package type and package count.
3. A price, when given, must meet every rule in **Price** below.

#### Request / Response / Outcome

A price, wherever it appears in this epic, is always this shape:

**Price**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Amount | `BigDecimal` | Non-negative; at most 12 integer digits; decimal places no more than the currency allows | ✅ | The exact amount |
| Currency | `String` | A real ISO currency code with a fixed number of decimal places | ✅ | Which currency the amount is in |

**Request — adding one item**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Name | `String` | 1–255 characters, trimmed; unique among active items | ✅ | The item's name |
| Description | `String` | 1–255 characters, trimmed | ❌ | A short description of the item |
| Catalogue Item Variants | `CatalogueItemVariant[]` | At least one entry; names unique within the item; SKUs unique among the organization's active variants | ✅ | The ways the item is sold, stored together with the item |

**CatalogueItemVariant**

A variant, wherever it is sent in this epic, is always this shape:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Name | `String` | 1–255 characters, trimmed; unique among the item's active variants | ❌ | Tells this variant apart from the item's others |
| SKU | `String` | 1–255 characters, trimmed; unique among the organization's active variants | ❌ | The business's own stock code for this variant |
| Sale Unit | `String` | 1–255 characters, trimmed | ✅ | What it is sold by, in the business's own words |
| Measure Value | `BigDecimal` | Greater than zero; at most 12 digits before the decimal point and 6 after; exact, never rounded; given together with Measure Unit or not at all | ❌ | How much of the measure unit one sale unit holds |
| Measure Unit | `String` | 1–255 characters, trimmed; given together with Measure Value or not at all | ❌ | What the measure value is counted in |
| Package Type | `String` | 1–255 characters, trimmed; given together with Package Count or not at all | ❌ | What it is packed in |
| Package Count | `Int` | Whole number, at least 1; given together with Package Type or not at all | ❌ | How many packages make up one sale unit |
| Price | `Price` | See **Price** above | ❌ | Left out means the variant has no set price |

**Request — adding several at once**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Items | `CatalogueItem[]` | Empty by default. Each entry is the shape of **adding one item** above | ❌ | The items to add, all together or not at all |

**Response**

Response is empty. A successful add answers with nothing but a success status — no identifier for the item or variants just created.

**Outcome**

- The item and every variant are stored as active, each variant with its price if one was given.
- For a batch, every item and every variant is stored together, or none is.
- On any refusal, including a taken name or SKU, nothing at all is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid, reported together with the item or variant each belongs to - An item has no variants - A measure or a packaging is given only in half, naming the field left out |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - An active item already has that name - An active variant already has that SKU - Two variants of the same item share a name |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 2. User Uploads an Item's Image

**Who can reach this step: an owner or admin of the organization.**

- User picks an image for an existing, active item.
- We check it really is an image, store it, and make a smaller standard copy.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. Owner uploads a valid image for an active item | - The file is checked to really be a PNG, JPEG or WEBP - The original is stored as sent, alongside a smaller standard copy |
| 2. Owner uploads a second image later | - The new image replaces the old one |
| 3. User uploads an image for an item that is archived, or does not exist | - Rejected - Today this is reported as a server error — see [gap 1](#1-uploading-for-a-missing-or-archived-item-is-reported-as-a-server-error) |
| 4. User uploads a file whose filename extension does not match what the file really is | - Rejected as an invalid request and nothing is stored |
| 5. User uploads a file larger than the limit | - Should be rejected cleanly - Today it is not: the same stall affects this upload as the organization logo upload — see [Organization Onboarding step 2]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#2-user-uploads-a-logo) |
| 6. A member with the ordinary user role tries to upload | - Rejected |
| 7. The organization id, item id, or file name is missing from the request | - Rejected as an invalid request |

#### Requirements

1. Only PNG, JPEG and WEBP files are accepted, judged by the file's contents. The filename extension must agree with what the file really is.
2. A file may be up to 20 MB.
3. The smaller standard copy is at most 640×640. Uploading again replaces both the original and the smaller copy.
4. Only an active item can receive an image.

#### Request / Response / Outcome

**Request**

The body is the raw image file. Three values travel in the request's headers instead of the body:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form, sent as the `X-Organization-ID` header | ✅ | Which organization the item belongs to |
| Catalogue Item ID | `UUID` | Canonical 36-character form, sent as the `X-Catalogue-Item-ID` header | ✅ | Which item the image is for |
| File Name | `String` | 1–255 characters, trimmed; supported extension matching the actual format; sent as the `X-File-Name` header | ✅ | The original name of the file, kept alongside the stored image |
| Image | Binary | PNG, JPEG or WEBP; up to 20 MB | ✅ | The image itself, sent as the request body |

**Response**

Response is empty. A successful upload answers with nothing but a success status.

**Outcome**

- The image is stored exactly as uploaded, with a smaller standard copy beside it, replacing any image the item already had.
- The item's active or archived status is never changed by this step.
- On any refusal nothing is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization id header is missing - The item id header is missing - The file name header is missing or unsupported - The filename extension does not match what the file really is |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow it |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - The item does not exist, belongs to another organization, or is archived - The image cannot be read or processed - Unexpected error |

### 3. User Browses the Catalogue

**Who can reach this step: any member of the organization.**

- User opens the catalogue and sees every active item.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens the catalogue | - Every active item in the organization is returned, each with its name, status, and a picture if it has one |
| 2. An item has no image | - It appears in the list with nothing in place of a picture |
| 3. The organization has archived items | - They do not appear, and there is no way to list them again — the same gap as [Customer Book gap 3]({{ site.baseurl }}{% link epics/05-customer-book.md %}#3-archiving-is-final-and-archived-customers-cannot-be-found-again) |
| 4. The organization has no items yet | - An empty list is returned |

#### Requirements

1. Any member may browse the catalogue, whatever their role.
2. The list contains only active items.
3. Each entry carries a name, its status, and a picture if the item has one — but not its description or its variants, so no unit or price. See [gap 2](#2-browsing-the-catalogue-does-not-show-the-price-or-the-variants).

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
| Status | `CatalogueItemStatus` | `Active` or `Archived` | ✅ | Always `Active` here, since only active items are listed |
| Image URL | `String` | A working link, present only if the item has an image | ❌ | A picture of the item |

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 4. User Opens an Item

**Who can reach this step: any member of the organization.**

- User picks an item from the catalogue to see its full details and its variants.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens an item that exists | - Its name, description if any, picture if any, and every active variant are returned, oldest first |
| 2. The item has archived variants | - They are left out; only active variants are returned |
| 3. User opens an item that has been archived | - Its name, description and picture are still returned. Archiving hides an item from the list, not from a direct look-up. Its variants were archived with it, so none are returned |
| 4. User opens an item that does not exist | - Reported as a server error rather than "not found" — the same gap as [Customer Book gap 1]({{ site.baseurl }}{% link epics/05-customer-book.md %}#1-looking-up-a-customer-that-is-not-there-is-reported-as-a-server-error) |

#### Requirements

1. An item is looked up by identifier.
2. The full details include the description and the active variants, which the list in step 3 leaves out.
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
| Description | `String` | — | ❌ | Present only if the item has a description |
| Image URL | `String` | A working link | ❌ | Present only if the item has an image |
| Catalogue Item Variants | `CatalogueItemVariantDetails[]` | Active variants only, in the order they were added; may be empty for an archived item | ✅ | The ways the item is sold today |

**CatalogueItemVariantDetails**

A variant, wherever it is returned in this epic, is always this shape:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | Identifies the variant, used to open, change or archive it |
| Name | `String` | — | ❌ | Present only if the variant has a name |
| SKU | `String` | — | ❌ | Present only if the variant has a SKU |
| Sale Unit | `String` | — | ✅ | What it is sold by |
| Measure Value | `BigDecimal` | — | ❌ | Present only if given |
| Measure Unit | `String` | — | ❌ | Present only if given |
| Package Type | `String` | — | ❌ | Present only if given |
| Package Count | `Int` | — | ❌ | Present only if given |
| Price | `Price` | See **Price** in step 1 | ❌ | Present only if the variant has a price |

This answer does not say whether the item, or a variant opened in step 8, is archived. See [gap 3](#3-opening-an-item-does-not-say-whether-it-is-archived).

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - No item with that identifier - The person does not belong to the organization - Unexpected error |

### 5. User Updates an Item

**Who can reach this step: an owner or admin of the organization.**

- User changes an item's name or description. Variants are changed one at a time, in [step 9](#9-user-updates-a-variant).

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User changes an active item's details | - The change is saved - Fields left out of the request are left as they were |
| 2. User renames an item to a name another active item already has | - Rejected as a conflict |
| 3. User changes an item that has been archived, or does not exist | - Nothing happens, and it is reported as successful — the same gap as [Customer Book gap 2]({{ site.baseurl }}{% link epics/05-customer-book.md %}#2-changes-to-an-archived-customer-are-silently-discarded) |
| 4. A member with the ordinary user role tries to make a change | - Rejected |

#### Requirements

1. Only active items can be changed.
2. A field left out of the request keeps its current value; there is no way to clear a description once set. See [gap 5](#5-an-optional-detail-can-be-changed-but-never-cleared).
3. A rename must still leave the name unique among active items.
4. This step never touches the item's variants.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Which item to change |
| Name | `String` | 1–255 characters, trimmed; unique among active items | ❌ | Leave out to keep the current name |
| Description | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep the current description. There is no way to send "no description" |

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
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 6. User Archives an Item

**Who can reach this step: an owner or admin of the organization.**

- User retires an item no longer for sale, together with all its variants.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User archives an active item | - The item becomes archived and leaves the catalogue - Every active variant of the item is archived in the same step - The item's name and its variants' SKUs become free for reuse - Its image, if any, is kept |
| 2. User archives an item that is already archived, or does not exist | - Nothing happens, and it is reported as successful |
| 3. User wants an archived item back | - Not possible. There is no way to reverse archiving — the same gap as [Customer Book gap 3]({{ site.baseurl }}{% link epics/05-customer-book.md %}#3-archiving-is-final-and-archived-customers-cannot-be-found-again) |

#### Requirements

1. An archived item is kept in full, including its image and its variants, and can still be opened directly by identifier.
2. Archiving an item and its variants happens together, or not at all.
3. Archiving cannot be undone.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Which item to archive |

**Response**

Response is empty, whether the item was archived just now, was already archived, or never existed.

**Outcome**

- An active item and all its active variants become archived, and the item leaves the catalogue. Their records and the image are kept, the item's name is free for a new active item, and the variants' SKUs are free for new active variants.
- An already-archived or missing item is left exactly as it was.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 7. User Adds a Variant

**Who can reach this step: an owner or admin of the organization.**

- User adds another way of selling an existing, active item.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User adds a variant to an active item | - Stored as active under that item |
| 2. User gives a SKU already used by an active variant in the organization | - Rejected as a conflict, naming the SKU |
| 3. User gives a name already used by another active variant of the same item | - Rejected as a conflict |
| 4. User gives a field that breaks its rule — a bad price, a measure value of zero or less, a package count below 1 | - Rejected, every problem reported together |
| 5. User gives a measure value without a measure unit, or the other way round; or a package type without a package count, or the other way round | - Rejected, naming the field left out |
| 6. User adds a variant to an item that is archived, or does not exist | - Rejected - Today this is reported as a server error — see [gap 1](#1-uploading-for-a-missing-or-archived-item-is-reported-as-a-server-error) |
| 7. A member with the ordinary user role tries to add a variant | - Rejected |

#### Requirements

1. A variant can only be added to an active item.
2. The variant follows every rule in **CatalogueItemVariant** in [step 1](#1-user-adds-an-item).

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Which item the variant belongs to |
| Catalogue Item Variant | `CatalogueItemVariant` | See **CatalogueItemVariant** in step 1 | ✅ | The variant to add |

**Response**

Response is empty. A successful add answers with nothing but a success status — no identifier for the variant just created.

**Outcome**

- The variant is stored as active under the item, with its price if one was given.
- On any refusal nothing is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid, reported together - A measure or a packaging is given only in half, naming the field left out |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - An active variant already has that SKU - Another active variant of the item already has that name |
| 500 | `INTERNAL_SERVER_ERROR` | - The item does not exist, belongs to another organization, or is archived - The person does not belong to the organization - Unexpected error |

### 8. User Opens a Variant

**Who can reach this step: any member of the organization.**

- User looks at one variant on its own.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens a variant that exists | - Its details are returned, together with the identifier of the item it belongs to |
| 2. User opens a variant that has been archived | - Its details are still returned, without saying it is archived — see [gap 3](#3-opening-an-item-does-not-say-whether-it-is-archived) |
| 3. User opens a variant that does not exist | - Reported as a server error rather than "not found", the same as opening a missing item in [step 4](#4-user-opens-an-item) |

#### Requirements

1. A variant is looked up by its own identifier.
2. An archived variant can still be opened directly.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form, in the address | ✅ | Which variant to open |

**Response**

Every field of the **CatalogueItemVariantDetails** shape defined in [step 4](#4-user-opens-an-item), plus:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | The item this variant belongs to, used to open it |

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - No variant with that identifier - The person does not belong to the organization - Unexpected error |

### 9. User Updates a Variant

**Who can reach this step: an owner or admin of the organization.**

- User changes one variant's details — its name, SKU, sale unit, measure, packaging or price.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User changes an active variant's details | - The change is saved - Fields left out of the request are left as they were |
| 2. User changes the SKU to one another active variant in the organization already has | - Rejected as a conflict, naming the SKU |
| 3. User renames the variant to a name another active variant of the same item already has | - Rejected as a conflict |
| 4. User gives a field that breaks its rule | - Rejected, every problem reported together |
| 5. User sends a measure value without a measure unit, or the other way round; or a package type without a package count, or the other way round | - Rejected, naming the field left out - To change one half of a measure or packaging, both halves are sent |
| 6. User changes a variant that has been archived (including every variant of an archived item), or does not exist | - Nothing happens, and it is reported as successful, the same as changing an archived item in [step 5](#5-user-updates-an-item) |
| 7. A member with the ordinary user role tries to make a change | - Rejected |

#### Requirements

1. Only active variants can be changed.
2. A field left out of the request keeps its current value; there is no way to clear an optional detail or a price once set. See [gap 4](#4-a-price-can-be-changed-but-never-removed) and [gap 5](#5-an-optional-detail-can-be-changed-but-never-cleared).
3. A change must still leave the name unique within the item and the SKU unique within the organization's active variants.
4. A measure value and a measure unit are sent together or left out together, and so are a package type and a package count.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | Which variant to change |
| Name | `String` | 1–255 characters, trimmed; unique among the item's active variants | ❌ | Leave out to keep the current name |
| SKU | `String` | 1–255 characters, trimmed; unique among the organization's active variants | ❌ | Leave out to keep the current SKU |
| Sale Unit | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep the current sale unit |
| Measure Value | `BigDecimal` | Greater than zero; at most 12 digits before the decimal point and 6 after; exact, never rounded; sent together with Measure Unit or not at all | ❌ | Leave out, with Measure Unit, to keep the current measure |
| Measure Unit | `String` | 1–255 characters, trimmed; sent together with Measure Value or not at all | ❌ | Leave out, with Measure Value, to keep the current measure |
| Package Type | `String` | 1–255 characters, trimmed; sent together with Package Count or not at all | ❌ | Leave out, with Package Count, to keep the current packaging |
| Package Count | `Int` | Whole number, at least 1; sent together with Package Type or not at all | ❌ | Leave out, with Package Type, to keep the current packaging |
| Price | `Price` | See **Price** in step 1 | ❌ | Leave out to keep the current price. There is no way to send "no price" |

**Response**

Response is empty. A successful change answers with nothing but a success status — and so does a change that quietly did nothing.

**Outcome**

- For an active variant the details are replaced as described and nothing else changes.
- For an archived or missing variant nothing is stored, and the answer is the same as success.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid - A measure or a packaging is sent only in half, naming the field left out |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - An active variant already has that SKU - Another active variant of the item already has that name |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 10. User Archives a Variant

**Who can reach this step: an owner or admin of the organization.**

- User retires one way of selling an item while the item stays on sale.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User archives an active variant while the item has other active variants | - The variant becomes archived and no longer appears when the item is opened - Its name and SKU become free for reuse |
| 2. User archives the last active variant of an active item | - Rejected as a conflict; the item must keep at least one active variant. To retire it, archive the item in [step 6](#6-user-archives-an-item) |
| 3. User archives a variant that is already archived (including every variant of an archived item), or does not exist | - Nothing happens, and it is reported as successful |
| 4. User wants an archived variant back | - Not possible. There is no way to reverse archiving |
| 5. A member with the ordinary user role tries to archive a variant | - Rejected |

#### Requirements

1. An archived variant is kept in full and can still be opened directly by identifier.
2. An active item always keeps at least one active variant.
3. Archiving cannot be undone.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | Which variant to archive |

**Response**

Response is empty, whether the variant was archived just now, was already archived, or never existed.

**Outcome**

- An active variant becomes archived. Its record is kept, and its name and SKU are free for new active variants.
- An already-archived or missing variant is left exactly as it was.
- A refused last variant is left exactly as it was.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - It is the last active variant of an active item |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### Known gaps and open questions

Everything above describes what the product does today. Nothing in this section exists yet; each one needs a product answer before it can be built.

#### 1. Uploading for a missing or archived item is reported as a server error

Naming an item that does not exist, belongs to another organization, or has been archived is refused as a server error — both when uploading its image and when adding a variant to it. Picking an item a colleague just archived, or following a stale link, is ordinary, not a failure on our side.

**To decide:** whether this should be a plain refusal instead, naming what was wrong with the item.

#### 2. Browsing the catalogue does not show the price or the variants

The list in step 3 gives a name, a status, and a picture — never the variants, so never a unit or a price. Seeing what anything costs means opening each item, which makes a long list close to useless for comparing prices at a glance.

**To decide:** whether variants or prices should be shown in the list, and if the concern is response size for a very large catalogue, whether that is better solved by adding them than by leaving them out.

#### 3. Opening an item does not say whether it is archived

An archived item answers a direct look-up exactly like an active one, and so does an archived variant in [step 8](#8-user-opens-a-variant). Combined with the silent no-op when changing an archived item ([step 5](#5-user-updates-an-item)) or variant ([step 9](#9-user-updates-a-variant)), the edit screen for an archived item or variant looks perfectly editable, with nothing warning that a save will quietly do nothing.

**To decide:** whether the answers should include the item's or variant's status, the way [browsing the catalogue](#3-user-browses-the-catalogue) already does.

#### 4. A price can be changed but never removed

Updating a variant can set or replace a price, but there is no way to say "this variant no longer has a price". Once set, a price can only be changed, never cleared.

**To decide:** whether removing a price should be possible, and if so how the request should say "clear this" as distinct from "leave this alone".

#### 5. An optional detail can be changed but never cleared

The same limit as [gap 4](#4-a-price-can-be-changed-but-never-removed) applies to an item's description and to a variant's name, SKU, measure value, measure unit, package type and package count. Once set, each can only be replaced, never removed.

**To decide:** whether clearing these should be possible, and how a request would say so.

{% include abbreviations.md %}
