---
title: Catalogue
---

# Catalogue

> **Planned change — not built yet.** This page describes the agreed catalogue with variants and selling units. Until that work ships, the product still behaves as before: each item has a free-text unit and one optional price in any ISO currency, and items are only `Active` or `Archived`. The engineering description of today's behavior is kept in the Catalogue feature doc until the change ships.

### Overview

Every organization keeps a catalogue of what it sells. An item is the thing a customer recognises — a Coca-Cola, a T-shirt, tomatoes. Each item comes in one or more **variants**: the exact versions that are sold, each with its own price and its own way of being sold (a 1 L bottle, a pack of 6, a box of 12 kg, loose by the kilo). Future orders will pick variants from the catalogue.

### Related / Out of scope

- **Related** — [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}). An organization must exist first, and every request here names which organization it is for.
- **Related** — [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}). Both work the same way — add, browse, open, update, archive — for a different kind of record. Anything already written there is not repeated here.
- **Not built yet** — Orders. The catalogue exists so that orders can pick variants from it later, but nothing places or records an order yet.
- **Not built yet** — Search, filtering and paging. The list returns every active item in one go.
- **Not built yet** — Pricing by time. Nothing can be billed per hour or per day. A service can be listed as a quantity of 1 piece, for example "1 session".
- **Not built yet** — Images for individual variants. Only the item has an image.

### Requirements across the epic

Every request in this epic names the organization it applies to, and only touches that organization's catalogue.

#### Functional

1. An item has a name, may have a description, and has at least one variant. There is no upper limit on variants. The price and the way something is sold belong to each variant, not to the item.
2. A variant must have a price and a selling unit. It may also have a SKU (stock-keeping unit), a label, a description, and options such as colour or size. A simple item is just an item with one variant that leaves all of these out.
3. A variant's price is the full price of exactly the quantity its selling unit states, including everything inside it. "2 caramels" is its own variant with its own price, not twice the price of one. A pack of 6 bottles is priced as the whole pack. Loose tomatoes sold as "1 kilogram" are priced per kilogram.
4. Each variant has its own currency. Variants of the same item may use different currencies.
5. An item's name must be unique among the organization's active items. Archiving an item frees its name for reuse.
6. A SKU, when given, must be unique among all of the organization's non-archived variants, across every item. Upper and lower case count as different. Archiving the variant, or its item, frees the SKU for reuse.
7. Items and variants are **archived**, never deleted. Archiving an item also archives all of its variants. Archiving an item's last non-archived variant also archives the item.
8. An item may carry one image. Uploading a new one replaces it.

#### Non-functional

1. Every request needs a signed-in person who has finished personal onboarding and belongs to the organization named in the request. Someone outside the organization is refused with a server error — the same gap as [Organization Onboarding gap 3]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#3-not-belonging-to-an-organization-is-reported-as-a-server-error).
2. Owners, admins and ordinary members can all read. Only owners and admins can add, change, archive, or upload an image.
3. Prices and the numbers in a selling unit are exact and never rounded. A price is kept with exactly two decimal places. The numbers in a selling unit keep exactly the decimal places they were given.
4. When a request contains several problems at once, all of them are reported together. Each problem names the exact item, and the exact variant within it, that caused it.
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
7. [User Archives a Variant](#7-user-archives-a-variant)

### Prerequisites

**Item status** — an item is either `Active` or `Archived`. New items start out `Active`. Archiving is one-way.

**Variant status** — a variant is `Active`, `Inactive`, `OutOfStock` or `Archived`. Variants added with a new item start out `Active`. A variant added to an existing item starts with the status given — `Active`, `Inactive` or `OutOfStock` — or `Active` if none is given.
- Owners and admins can move a variant freely between `Active`, `Inactive` and `OutOfStock`, in any direction, by updating the item.
- `Archived` is reached only by archiving, and it is one-way.
- `Inactive` and `OutOfStock` variants are still shown everywhere an `Active` one is. They count toward the item's variant count and "from" prices, and they keep their SKU reserved.
- An item's status never turns into `Inactive` or `OutOfStock`. Only variants carry those statuses.

**Options** — words the business chooses itself to tell variants apart, written as a name and a value: `Colour: Red`, `Size: M`, `Flavour: Peanut`. There is no fixed list. Two variants of the same item may have identical options.

**Selling unit** — how one variant is sold. It has up to three parts. Each part is a number and a unit:

| Part | Meaning | Required |
| --- | --- | --- |
| Quantity | What one sale is — the amount the price covers | Yes |
| Per unit | What each sold unit contains: how many containers, and which kind | No |
| Unit size | The size of each single thing | No |

- There are three groups of units:
  - **Measurement units:** gram, kilogram, millilitre, litre, millimetre, centimetre, metre and square metre.
  - **Package units:** pack, box and piece. They say how something is sold or grouped.
  - **Per units:** bag, bottle, can, jar and piece. They are the containers or pieces a customer sees, as in "per bottle".
- The quantity can use any unit from any group. The per-unit part uses only per units. The unit-size part uses only measurement units.
- Every number is greater than 0 and at most 99999. A number may have decimal places, and is never rounded.
- Any combination of the three parts is accepted.

| Example | Quantity | Per unit | Unit size |
| --- | --- | --- | --- |
| 1 caramel | 1 piece | — | — |
| 2 caramels | 2 piece | — | — |
| A 1 L bottle of Coca-Cola | 1 bottle | — | 1 litre |
| A pack of 6 × 1 L bottles | 1 pack | 6 bottle | 1 litre |
| A box of 12 kg of tomatoes | 1 box | — | 12 kilogram |
| Loose tomatoes, priced per kg | 1 kilogram | — | — |
| A box of 100 screws | 1 box | 100 piece | — |

**Price** — an exact amount plus a currency. Only EUR, USD and GBP are accepted; any other currency is refused. Every amount is kept with exactly two decimal places. Every rule is in **Price** in [step 1](#1-user-adds-an-item).

### 1. User Adds an Item

**Who can reach this step: an owner or admin of the organization.**

- The user gives the item a name, optionally a description, and at least one variant. Each variant has a price and a selling unit.
- Several items can be added in one go.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User adds a simple item with one variant, such as a 1 L bottle of Coca-Cola | - Stored as an active item with one active variant |
| 2. User adds an item with several variants, such as a protein bar in three flavours, each with its own SKU and options | - The item and every variant are stored together, each variant active |
| 3. User adds variants that differ only in quantity, such as 1 caramel and 2 caramels | - Each variant keeps the price it was given; nothing is worked out from another variant's price |
| 4. User adds variants in different currencies | - Accepted; each variant keeps its own currency |
| 5. User adds an item with no variants | - Rejected |
| 6. User adds several items at once | - All are stored together or none are |
| 7. User adds an item whose name is already taken by an active item | - Rejected as a conflict, naming what clashed |
| 8. User gives a SKU that a non-archived variant already has, in this request or already in the catalogue | - Rejected as a conflict, naming what clashed |
| 9. User breaks a rule in **Price**, **Selling unit**, or the option rules below | - Rejected, naming the item, the variant, and the part at fault |
| 10. User submits several bad entries at once | - Every problem is reported together, each tied to its item and variant |
| 11. A member with the ordinary user role tries to add an item | - Rejected |

#### Requirements

1. An item needs a name and at least one variant. The description is optional.
2. Every variant needs a price and a selling unit. The SKU, label, description and options are optional.
3. Every price, selling unit and option must follow the rules in the tables below.

#### Request / Response / Outcome

These shapes are used throughout this epic.

**Price**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Amount | `BigDecimal` | Non-negative; at most 12 integer digits; at most 2 decimal places; never rounded | ✅ | The exact amount |
| Currency | `Currency` | `EUR`, `USD` or `GBP` | ✅ | Which currency the amount is in |

**CatalogueItemVariantSaleUnit**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Quantity | `CatalogueItemVariantQuantity` | See **CatalogueItemVariantQuantity** | ✅ | What one sale is, such as 1 pack or 1 kilogram |
| Per Unit | `CatalogueItemVariantPerUnit` | See **CatalogueItemVariantPerUnit** | ❌ | What each sold unit contains, such as 6 bottles |
| Unit Size | `CatalogueItemVariantUnitSize` | See **CatalogueItemVariantUnitSize** | ❌ | The size of each single thing, such as 1 litre |

**CatalogueItemVariantQuantity**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Value | `BigDecimal` | Greater than 0; at most 99999; never rounded | ✅ | How many |
| Unit | `CatalogueItemVariantUnit` | Any **UnitSize**, **PackageUnit** or **PerUnit** value | ✅ | Of what |

**CatalogueItemVariantPerUnit**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Value | `BigDecimal` | Greater than 0; at most 99999; never rounded | ✅ | How many in each sold unit |
| Unit | `PerUnit` | See **PerUnit** | ✅ | What kind of container or piece |

**CatalogueItemVariantUnitSize**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Value | `BigDecimal` | Greater than 0; at most 99999; never rounded | ✅ | The size |
| Unit | `UnitSize` | See **UnitSize** | ✅ | What it is measured in |

**UnitSize** — `Gram`, `Kilogram`, `Millilitre`, `Litre`, `Millimetre`, `Centimetre`, `Metre` or `SquareMetre`.

**PackageUnit** — `Pack`, `Box` or `Piece`.

**PerUnit** — `Bag`, `Bottle`, `Can`, `Jar` or `Piece`.

**Request — adding one item**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Name | `String` | 1–255 characters, trimmed; unique among active items | ✅ | The item's name |
| Description | `String` | 1–255 characters, trimmed | ❌ | A longer description of the item |
| Variants | `CatalogueItemVariant[]` | At least one entry; no upper limit | ✅ | The versions of the item that are sold |

**CatalogueItemVariant**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| SKU | `String` | 1–255 characters, trimmed; case-sensitive; unique among the organization's non-archived variants | ❌ | The business's own stock code |
| Label | `String` | 1–255 characters, trimmed | ❌ | A short label for this variant, such as "1L Lemon bottle" for the item "COKE" |
| Description | `String` | 1–255 characters, trimmed | ❌ | A longer description of this variant |
| Options | `Map<String, String>` | Empty by default; each name and value 1–255 characters, trimmed; names unique within the variant; no limit on how many | ❌ | What sets this variant apart, such as colour and size |
| Price | `Price` | See **Price** | ✅ | The full price of the quantity its selling unit states |
| Selling Unit | `CatalogueItemVariantSaleUnit` | See **CatalogueItemVariantSaleUnit** | ✅ | How this variant is sold |

**Request — adding several at once**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Items | `CatalogueItem[]` | Empty by default. Each entry has the shape above | ❌ | The items to add, all together or not at all |

**Response**

Response is empty. A successful add answers with nothing but a success status. It does not return an identifier for the item or its variants.

**Outcome**

- The item is stored as active, and every one of its variants is stored as active.
- For a batch, every item is stored together, or none is.
- If anything is refused, including a name or SKU that is already taken, nothing at all is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid, reported together with the item and variant each belongs to |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - An active item already has that name - A non-archived variant already has that SKU |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 2. User Uploads an Item's Image

**Who can reach this step: an owner or admin of the organization.**

- User picks an image for an existing, active item.
- We check it really is an image, store it, and make a smaller standard copy.
- The image belongs to the item as a whole, not to any one variant.

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
- This step never changes the status of the item or of any of its variants.
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

- User opens the catalogue and sees every active item, with what it costs at a glance.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens the catalogue | - Every active item in the organization is returned, each with its name, description if any, status, picture if any, "from" prices, and number of variants |
| 2. An item's variants all use one currency | - One "from" price is shown: the lowest price among its non-archived variants |
| 3. An item's variants use more than one currency | - One "from" price is shown for each currency, each the lowest price in that currency |
| 4. Some of an item's variants are `Inactive` or `OutOfStock` | - They still count toward the "from" prices and the variant count |
| 5. Some of an item's variants are archived | - They do not count toward the "from" prices or the variant count |
| 6. An item has no image | - It appears in the list with nothing in place of a picture |
| 7. The organization has archived items | - They do not appear, and there is no way to list them again — the same gap as [Customer Book gap 3]({{ site.baseurl }}{% link epics/05-customer-book.md %}#3-archiving-is-final-and-archived-customers-cannot-be-found-again) |
| 8. The organization has no items yet | - An empty list is returned |

#### Requirements

1. Any member may browse the catalogue, whatever their role.
2. The list contains only active items.
3. Each entry shows the lowest non-archived variant price in each currency, plus the number of non-archived variants. The variants themselves are not listed.

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
| Description | `String` | — | ❌ | Present only if the item has one |
| Status | `CatalogueItemStatus` | `Active` or `Archived` | ✅ | Always `Active` here, since only active items are listed |
| Image URL | `String` | A working link, present only if the item has an image | ❌ | A picture of the item |
| From Prices | `Price[]` | One entry per currency used by the item's non-archived variants; each is the lowest price in that currency | ✅ | What the item costs, at least |
| Variant Count | `Int` | 1 or more | ✅ | How many non-archived variants the item has |

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

- User picks an item from the catalogue to see it in full, including every variant still on offer.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens an active item | - Its name, description, status, picture if any, and every non-archived variant are returned, each variant with its status |
| 2. Some of the item's variants are archived | - Those variants are left out |
| 3. User opens an item that has been archived | - It is still returned, with status `Archived` and no variants, because archiving the item archived them all |
| 4. User opens an item that does not exist | - Reported as a server error rather than "not found" — the same gap as [Customer Book gap 1]({{ site.baseurl }}{% link epics/05-customer-book.md %}#1-looking-up-a-customer-that-is-not-there-is-reported-as-a-server-error) |

#### Requirements

1. An item is looked up by its identifier.
2. The answer includes the item's status and the status of each variant it lists.
3. Archived variants are never listed.
4. An archived item can still be opened directly.

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
| Description | `String` | — | ❌ | Present only if the item has one |
| Status | `CatalogueItemStatus` | `Active` or `Archived` | ✅ | Whether the item is still on offer |
| Variants | `CatalogueItemVariantDetails[]` | Non-archived variants only; empty for an archived item | ✅ | The versions of the item on offer |
| Image URL | `String` | A working link | ❌ | Present only if the item has an image |

**CatalogueItemVariantDetails**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | Identifies the variant; used to change or archive it |
| SKU | `String` | — | ❌ | Present only if the variant has one |
| Label | `String` | — | ❌ | Present only if the variant has one |
| Description | `String` | — | ❌ | Present only if the variant has one |
| Options | `Map<String, String>` | May be empty | ✅ | What sets this variant apart |
| Price | `Price` | See **Price** in step 1 | ✅ | The full price of the quantity its selling unit states |
| Selling Unit | `CatalogueItemVariantSaleUnit` | See **CatalogueItemVariantSaleUnit** in step 1 | ✅ | How this variant is sold |
| Status | `CatalogueItemVariantStatus` | `Active`, `Inactive` or `OutOfStock` | ✅ | Whether the variant is on sale, paused, or out of stock |

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

- The user changes an item's name or description.
- The user changes existing variants by their identifier, including moving a variant between `Active`, `Inactive` and `OutOfStock`.
- The user adds new variants.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User changes an active item's name or description | - The change is saved - Fields left out of the request are left as they were |
| 2. User clears the item's description, or a variant's SKU, label or description | - That value is removed |
| 3. User changes an existing variant's price, selling unit, options or status | - The change is saved for that variant only - Variants not mentioned are left exactly as they were |
| 4. User marks a variant `OutOfStock` or `Inactive`, and later `Active` again | - Each move is saved; any move among these three statuses is allowed |
| 5. User tries to set a variant to `Archived` through an update | - Rejected; variants are archived only through [step 7](#7-user-archives-a-variant) |
| 6. User adds a new variant to an active item | - Stored with the status given (`Active`, `Inactive` or `OutOfStock`), or `Active` if none is given |
| 7. User renames an item to a name another active item already has | - Rejected as a conflict |
| 8. User gives a variant a SKU that another non-archived variant already has | - Rejected as a conflict |
| 9. User changes an item that has been archived, or does not exist | - Nothing happens, and it is reported as successful — the same gap as [Customer Book gap 2]({{ site.baseurl }}{% link epics/05-customer-book.md %}#2-changes-to-an-archived-customer-are-silently-discarded) |
| 10. User names a variant that does not exist, belongs to another item, or is archived | - The whole update is refused, naming the variant, and nothing is stored |
| 11. A member with the ordinary user role tries to make a change | - Rejected |

#### Requirements

1. Only active items can be changed.
2. A field left out of the request keeps its current value. The item description, and a variant's SKU, label and description, can be cleared. A variant's price and selling unit can be replaced but never removed.
3. When given, a variant's options replace all of its existing options.
4. A variant's status can be set only to `Active`, `Inactive` or `OutOfStock`.
5. A new variant needs a price and a selling unit, just like one added in [step 1](#1-user-adds-an-item).
6. A rename must leave the item name unique among active items, and every SKU unique among non-archived variants.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Which item to change |
| Name | `String` | 1–255 characters, trimmed; unique among active items | ❌ | Leave out to keep the current name |
| Description | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep the current description; can be cleared |
| Variants | `CatalogueItemVariantUpdate[]` | Empty by default | ❌ | Variants to change or add; variants not listed stay as they are |

**CatalogueItemVariantUpdate**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ❌ | Given: change that variant. Left out: add a new variant |
| SKU | `String` | Same as in **CatalogueItemVariant** | ❌ | Leave out to keep; can be cleared |
| Label | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep; can be cleared |
| Description | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep; can be cleared |
| Options | `Map<String, String>` | Same as in **CatalogueItemVariant** | ❌ | Leave out to keep; when given, replaces all options |
| Price | `Price` | See **Price** in step 1; must be given when adding a new variant | ❌ | Leave out to keep the current price |
| Selling Unit | `CatalogueItemVariantSaleUnit` | See **CatalogueItemVariantSaleUnit** in step 1; must be given when adding a new variant | ❌ | Leave out to keep the current selling unit |
| Status | `CatalogueItemVariantStatus` | `Active`, `Inactive` or `OutOfStock` | ❌ | Leave out to keep the current status. For a new variant, the status it starts with; left out, it starts `Active` |

**Response**

Response is empty. A successful change answers with nothing but a success status, and so does a change that quietly did nothing.

**Outcome**

- For an active item, the named fields and variants are changed, new variants are added with the status given (or `Active`), and nothing else changes.
- For an archived or missing item nothing is stored, and the answer is the same as success.
- If anything is refused, nothing at all is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid, reported together with the variant each belongs to - A variant status of `Archived` was sent - A new variant is missing its price or selling unit - A named variant does not exist, belongs to another item, or is archived |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - An active item already has that name - A non-archived variant already has that SKU |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 6. User Archives an Item

**Who can reach this step: an owner or admin of the organization.**

- The user retires an item that is no longer for sale, along with all of its variants.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User archives an active item | - The item and every one of its variants become archived - The item leaves the catalogue - Its name, and every SKU its variants held, become free for reuse - Its image, if any, is kept |
| 2. User archives an item that is already archived, or does not exist | - Nothing happens, and it is reported as successful |
| 3. User wants an archived item back | - Not possible. There is no way to reverse archiving — the same gap as [Customer Book gap 3]({{ site.baseurl }}{% link epics/05-customer-book.md %}#3-archiving-is-final-and-archived-customers-cannot-be-found-again) |

#### Requirements

1. Archiving an item archives all of its variants with it.
2. An archived item is kept in full, including its image and its archived variants, and can still be opened directly by its identifier.
3. Archiving cannot be undone.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Which item to archive |

**Response**

Response is empty, whether the item was archived just now, was already archived, or never existed.

**Outcome**

- An active item and all of its variants become archived, and the item leaves the catalogue. The record and image are kept. The name and the variants' SKUs become free for reuse.
- An already-archived or missing item is left exactly as it was.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 7. User Archives a Variant

**Who can reach this step: an owner or admin of the organization.**

- The user retires one variant that is no longer sold, such as a discontinued flavour, and leaves the rest of the item alone.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User archives one of several non-archived variants | - That variant becomes archived and its SKU becomes free for reuse - The item and its other variants are unchanged |
| 2. User archives the item's last non-archived variant | - The variant becomes archived, and the item is archived with it, exactly as in [step 6](#6-user-archives-an-item) |
| 3. User archives a variant that is already archived, or does not exist | - Nothing happens, and it is reported as successful, the same as archiving an item |
| 4. User names a variant that belongs to a different item from the one named | - Refused, naming the variant, and nothing changes |
| 5. User wants an archived variant back | - Not possible. Archiving cannot be undone |
| 6. A member with the ordinary user role tries to archive a variant | - Rejected |

#### Requirements

1. Archiving a variant is one-way. The variant is kept, but it is no longer listed, counted, or priced anywhere in the catalogue.
2. An item never keeps active status with no non-archived variants. Archiving its last one archives the item.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | The item the variant belongs to |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | Which variant to archive |

**Response**

Response is empty, whether the variant was archived just now, was already archived, or never existed.

**Outcome**

- The variant becomes archived and its SKU becomes free for reuse.
- An already-archived or missing variant is left exactly as it was.
- If it was the item's last non-archived variant, the item becomes archived too: it leaves the catalogue, its name becomes free, and its image is kept.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - The named variant belongs to a different item from the one named; the error names the variant |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### Known gaps and open questions

Everything above describes the agreed product. Nothing in this section has been decided yet, and each item needs a product answer before it can be built.

#### 1. Uploading for a missing or archived item is reported as a server error

Naming an item that does not exist, belongs to another organization, or has been archived is refused as a server error. Picking an item a colleague just archived, or following a stale link, is ordinary, not a failure on our side.

**To decide:** whether this should be a plain refusal instead, naming what was wrong with the item.

{% include abbreviations.md %}
