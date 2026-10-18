---
title: Catalogue
---

# Catalogue

> **Implementation and verification in progress — not released.** The catalogue with variants and selling units replaces the old item-only catalogue. Final checks will be completed one operation at a time; item-image upload checks already pass. The separate status and `isArchived` rules described below are planned and are not implemented yet.

### Overview

Every organization keeps a catalogue of what it sells. An item is the thing a customer recognises — a Coca-Cola, a T-shirt, tomatoes. Each item comes in one or more **variants**: the exact versions that are sold, each with its own way of being sold and, when set, its own price (a 1 L bottle, a pack of 6, a box of 12 kg, loose by the kilo). Future orders will pick variants from the catalogue.

### Related / Out of scope

- **Related** — [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}). An organization must exist first, and every request here names which organization it is for.
- **Related** — [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}). Both work the same way — add, browse, open, update, archive — for a different kind of record. Anything already written there is not repeated here.
- **Not built yet** — Orders. The catalogue exists so that orders can pick variants from it later, but nothing places or records an order yet. Whether a variant with no set price can be ordered is decided when orders are built.
- **Not built yet** — Search, filtering and paging. The list returns every non-archived item in one go.
- **Not built yet** — Pricing by time. Nothing can be billed per hour or per day. A service can be listed as a quantity of 1 piece, for example "1 session".
- **Not built yet** — Images for individual variants. Only the item has an image.

### Requirements across the epic

Every request in this epic names the organization it applies to, and only touches that organization's catalogue.

#### Functional

1. An item has a name, may have a description, and has at least one variant. There is no upper limit on variants. The price and the way something is sold belong to each variant, not to the item.
2. A variant must have a selling unit. It may also have a price, a SKU (stock-keeping unit), a label, a description, and attributes such as colour or size. A simple item is just an item with one variant that leaves all of these out.
3. A variant without a price has no set price yet — for example, price on request or made to order. It is still listed and opened like any other variant.
4. A variant's price, when given, is the full price of exactly the quantity its selling unit states, including everything inside it. "2 caramels" is its own variant with its own price, not twice the price of one. A pack of 6 bottles is priced as the whole pack. Loose tomatoes sold as "1 kilogram" are priced per kilogram.
5. Each priced variant has its own currency, as part of its price. Variants of the same item may use different currencies. A variant with no price has no currency.
6. An item's name must be unique among the organization's non-archived items. Archiving an item frees its name for reuse.
7. A SKU, when given, must be unique among all of the organization's non-archived variants, across every item. Upper and lower case count as different. Archiving the variant, or its item, frees the SKU for reuse.
8. Items and variants are **archived**, never deleted. Archiving an item also archives all of its variants. An item's last non-archived variant cannot be archived on its own; to retire it, archive the item.
9. An item may carry one image. Uploading a new one replaces it.

#### Non-functional

1. Every request needs a signed-in person who has finished personal onboarding and belongs to the organization named in the request. Someone outside the organization is refused with a server error — the same gap as [Organization Onboarding gap 3]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#3-not-belonging-to-an-organization-is-reported-as-a-server-error).
2. Owners, admins and ordinary members can all read. Only owners and admins can add, change, archive, or upload an image.
3. Prices and the numbers in a selling unit are exact and never rounded. A price is kept with exactly two decimal places. The numbers in a selling unit keep exactly the decimal places they were given.
4. When a request contains several validation problems at once, all of them are reported together. Each problem names the exact item, and the exact variant within it, that caused it. A currency that is not exactly the uppercase code `EUR`, `USD` or `GBP` is rejected as a bad request during request decoding, before field validation and accumulation.
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
8. [User Looks Up Chosen Items](#8-user-looks-up-chosen-items)
9. [User Checks a Name or SKU Is Free](#9-user-checks-a-name-or-sku-is-free)

### Prerequisites

**Item status** — an item is `Active` or `Inactive`. New items start out `Active`, and owners and admins can change this status while the item is not archived.

**Variant status** — a variant is `Active`, `Inactive` or `OutOfStock`. Every new variant starts out `Active`, including one added to an existing item.
- Owners and admins can move a variant freely between `Active`, `Inactive` and `OutOfStock`, in any direction, by updating the item.
- `Inactive` and `OutOfStock` variants are still shown in item details and catalogue summaries, and they keep their SKU reserved.
- An `Inactive` item remains visible and editable while it is not archived.

**Archiving** — `isArchived` is a separate flag on both items and variants. New items and variants have `isArchived = false`. Archiving sets the flag to `true`, preserves the item's or variant's status, and cannot be undone. Archiving an item sets the flag on all of its variants while preserving each variant's status. Only the flag determines whether an item or variant is archived.

**Attributes** — words the business chooses itself to tell variants apart, written as a name and a value: `Colour: Red`, `Size: M`, `Flavour: Peanut`. There is no fixed list. Two variants of the same item may have identical attributes.

**Selling unit** — how one variant is sold. It has up to three parts. Each part is a number and a unit:

| Part | Meaning | Required |
| --- | --- | --- |
| Quantity | What one sale is — the amount the price covers | Yes |
| Per unit | What each sold unit contains: how many containers, and which kind | No |
| Unit size | The size of each single thing | No |

- There are three groups of units:
  - **Measurement units:** gram, kilogram, millilitre, litre, millimetre, centimetre, metre and square metre.
  - **Package units:** pack and box. They say how something is sold or grouped.
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

**Price** — optional on every variant. When given, it is an exact amount plus a currency, always together: a price is given in full or left out entirely. The currency must be exactly the uppercase code `EUR`, `USD` or `GBP`; lowercase, surrounding-space, and other spellings are rejected without trimming or uppercasing. An amount may have at most two decimal places when sent; fewer decimal places are kept by appending zeroes until it has exactly two, and no amount is rounded. Every rule is in **Price** in [step 1](#1-user-adds-an-item).

### 1. User Adds an Item

**Who can reach this step: an owner or admin of the organization.**

- The user gives the item a name, optionally a description, and at least one variant. Each variant has a selling unit and, optionally, a price.
- Several items can be added in one go.
- Before adding, the user can check whether a name or SKU is still free, as in [step 9](#9-user-checks-a-name-or-sku-is-free).

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User adds a simple item with one variant, such as a 1 L bottle of Coca-Cola | - Stored as an active, non-archived item with one active, non-archived variant |
| 2. User adds an item with several variants, such as a protein bar in three flavours, each with its own SKU and attributes | - The item and every variant are stored together, each variant active and non-archived |
| 3. User adds variants that differ only in quantity, such as 1 caramel and 2 caramels | - Each variant keeps the price it was given; nothing is worked out from another variant's price |
| 4. User adds variants in different currencies | - Accepted; each variant keeps its own currency |
| 5. User adds a variant with no price | - Stored with no set price |
| 6. User adds an item with no variants | - Rejected |
| 7. User adds several items at once | - All are stored together or none are |
| 8. User adds an item whose name is already taken by a non-archived item | - Rejected as a conflict, naming what clashed |
| 9. User gives a SKU that a non-archived variant already has, in this request or already in the catalogue | - Rejected as a conflict, naming what clashed |
| 10. User gives an invalid price amount or breaks a rule in **Selling unit** or the attribute rules below | - Rejected, naming the item, the variant, and the part at fault |
| 11. User gives a currency other than the exact uppercase codes `EUR`, `USD` or `GBP` | - Rejected as a bad request before field validation |
| 12. User submits several entries with validation problems at once | - Every validation problem is reported together, each tied to its item and variant |
| 13. A member with the ordinary user role tries to add an item | - Rejected |
| 14. User submits a batch with no items | - Rejected as invalid; nothing is stored |

#### Requirements

1. An item needs a name and at least one variant. The description is optional.
2. Every variant needs a selling unit. The price, SKU, label, description and attributes are optional.
3. Every price, selling unit and attribute must follow the rules in the tables below.
4. A batch must contain at least one item.

#### Request / Response / Outcome

These shapes are used throughout this epic.

**Price**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Amount | `BigDecimal` | Non-negative; at most 12 integer digits; 0–2 decimal places on input; stored with exactly 2 decimal places by appending zeroes; never rounded | ✅ | The exact amount |
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
| Unit | `CatalogueItemVariantUnitPerUnit` | See **PerUnit** | ✅ | What kind of container or piece |

**CatalogueItemVariantUnitSize**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Value | `BigDecimal` | Greater than 0; at most 99999; never rounded | ✅ | The size |
| Unit | `CatalogueItemVariantUnitUnitSize` | See **UnitSize** | ✅ | What it is measured in |

**UnitSize** — `Gram`, `Kilogram`, `Millilitre`, `Litre`, `Millimetre`, `Centimetre`, `Metre` or `SquareMetre`.

**PackageUnit** — `Pack` or `Box`.

**PerUnit** — `Bag`, `Bottle`, `Can`, `Jar` or `Piece`.

**Request — adding one item**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Name | `String` | 1–255 characters, trimmed; unique among non-archived items | ✅ | The item's name |
| Description | `String` | 1–255 characters, trimmed | ❌ | A longer description of the item |
| Variants | `CatalogueItemVariantInsert[]` | At least one entry; no upper limit | ✅ | The versions of the item that are sold |

**CatalogueItemVariantInsert**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| SKU | `String` | 1–255 characters, trimmed; case-sensitive; unique among the organization's non-archived variants | ❌ | The business's own stock code |
| Label | `String` | 1–255 characters, trimmed | ❌ | A short label for this variant, such as "1L Lemon bottle" for the item "COKE" |
| Description | `String` | 1–255 characters, trimmed | ❌ | A longer description of this variant |
| Attributes | `Map<String, String>` | Empty by default; each name and value 1–255 characters, trimmed; names unique within the variant; no limit on how many | ❌ | What sets this variant apart, such as colour and size |
| Price | `CatalogueItemVariantPrice` | See **Price** | ❌ | The full price of the quantity its selling unit states. Left out means the variant has no set price |
| Selling Unit | `CatalogueItemVariantSaleUnit` | See **CatalogueItemVariantSaleUnit** | ✅ | How this variant is sold |

**Request — adding several at once**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Items | `CatalogueItemPostRequest[]` | At least one entry. Each entry has the shape above | ❌ | The items to add, all together or not at all |

**Response — adding one item**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Identifies the new item, used to open it, change it, archive it, or upload its image |

**Response — adding several at once**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item IDs | `UUID[]` | Canonical 36-character form each; one per item added, in the same order as the request | ✅ | Identifies each new item |

Only item identifiers are returned. Variant identifiers are not; they are seen by opening the item, as in [step 4](#4-user-opens-an-item).

**Outcome**

- The item is stored as active, and every one of its variants is stored as active.
- For a batch, every item is stored together, or none is.
- If anything is refused, including a name or SKU that is already taken, nothing at all is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - A batch contains no items - One or more fields are invalid, reported together with the item and variant each belongs to |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request - The price currency was not exactly `EUR`, `USD` or `GBP` in uppercase - A variant is missing its required selling unit |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - A non-archived item already has that name - A non-archived variant already has that SKU |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 2. User Uploads an Item's Image

**Who can reach this step: an owner or admin of the organization.**

- User picks an image for an existing, non-archived item.
- We check it really is an image, store it, and make a smaller standard copy.
- The image belongs to the item as a whole, not to any one variant.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. Owner uploads a valid image for a non-archived item | - The file is checked to really be a PNG, JPEG or WEBP - The original is stored as sent, alongside a smaller standard copy |
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
4. Only a non-archived item can receive an image. Its `Active` or `Inactive` status does not change this.

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

- User opens the catalogue and sees every non-archived item with its non-archived variants.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens the catalogue | - Every non-archived item in the organization is returned, each with its name, status, picture if any, and non-archived variants |
| 2. A variant has a price | - That variant keeps its price and currency in the summary |
| 3. A variant has no price | - That variant is returned with no price |
| 4. Some of an item's variants are `Inactive` or `OutOfStock` | - They are included with their status |
| 5. Some of an item's variants are archived | - They are left out |
| 6. An item is `Inactive` but not archived | - It is included with its status |
| 7. An item has no image | - It appears in the list with nothing in place of a picture |
| 8. The organization has archived items | - They do not appear, and there is no way to list them again — the same gap as [Customer Book gap 3]({{ site.baseurl }}{% link epics/05-customer-book.md %}#3-archiving-is-final-and-archived-customers-cannot-be-found-again) |
| 9. The organization has no items yet | - An empty list is returned |

#### Requirements

1. Any member may browse the catalogue, whatever their role.
2. The list contains only non-archived items.
3. Each entry shows its non-archived item fields and every non-archived variant's summary fields in catalogue order. Archived variants are not listed.

#### Request / Response / Outcome

**Request**

Request is empty apart from naming the organization. The optional identifier filter is described in step 8; without it, all non-archived items are returned.

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Items | `CatalogueItemSummary[]` | Every non-archived item, oldest first, with its non-archived variants oldest first; may be empty | ✅ | The catalogue |

**CatalogueItemSummary**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Identifies the item, used to open it |
| Name | `String` | — | ✅ | The item's name |
| Status | `CatalogueItemStatus` | `Active` or `Inactive` | ✅ | The item's current status |
| Is Archived | `Boolean` | Always `false` here, since only non-archived items are listed | ✅ | Whether the item is archived |
| Image URL | `String` | A working link, present only if the item has an image | ❌ | A picture of the item |
| Variants | `CatalogueItemVariantSummary[]` | Every non-archived variant, at least one, in catalogue order | ✅ | The versions of the item that are sold |

**CatalogueItemVariantSummary**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | Identifies the variant |
| SKU | `String` | 1–255 characters, trimmed; case-sensitive | ❌ | The business's stock code |
| Label | `String` | 1–255 characters, trimmed | ❌ | A short label for the variant |
| Price | `CatalogueItemVariantPrice` | See **Price** in step 1 | ❌ | The full price of the variant's selling unit |
| Selling Unit | `CatalogueItemVariantSaleUnit` | See **CatalogueItemVariantSaleUnit** in step 1 | ✅ | How this variant is sold |
| Status | `CatalogueItemVariantStatus` | `Active`, `Inactive` or `OutOfStock` | ✅ | The variant's current status |
| Is Archived | `Boolean` | Always `false` here, since only non-archived variants are listed | ✅ | Whether the variant is archived |

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

- User picks a non-archived item from the catalogue to see it in full, including every non-archived variant still on offer.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens a non-archived item | - Its name, description, status, picture if any, and every non-archived variant are returned, each variant with its status |
| 2. Some of the item's variants are archived | - The item still opens normally - Those variants are left out; `Inactive` and `OutOfStock` variants are shown |
| 3. User opens an item that has been archived | - Not returned. It is treated exactly like an item that does not exist |
| 4. User opens an item that does not exist, or has been archived | - Reported as a server error rather than "not found" — the same gap as [Customer Book gap 1]({{ site.baseurl }}{% link epics/05-customer-book.md %}#1-looking-up-a-customer-that-is-not-there-is-reported-as-a-server-error) |

#### Requirements

1. An item is looked up by its identifier.
2. Only a non-archived item can be opened. An archived item cannot be opened.
3. The answer includes the item's status and the status of each variant it lists.
4. Archived variants are never listed. Archiving some of an item's variants does not stop the item being opened.

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
| Status | `CatalogueItemStatus` | `Active` or `Inactive` | ✅ | The item's current status |
| Is Archived | `Boolean` | Always `false` here, since only non-archived items can be opened | ✅ | Whether the item is archived |
| Variants | `CatalogueItemVariant[]` | Non-archived variants only; at least one entry | ✅ | The versions of the item on offer |
| Image URL | `String` | A working link | ❌ | Present only if the item has an image |

**CatalogueItemVariant**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | Identifies the variant; used to change or archive it |
| SKU | `String` | — | ❌ | Present only if the variant has one |
| Label | `String` | — | ❌ | Present only if the variant has one |
| Description | `String` | — | ❌ | Present only if the variant has one |
| Attributes | `Map<String, String>` | May be empty | ✅ | What sets this variant apart |
| Price | `CatalogueItemVariantPrice` | See **Price** in step 1 | ❌ | Present only if the variant has a price |
| Selling Unit | `CatalogueItemVariantSaleUnit` | See **CatalogueItemVariantSaleUnit** in step 1 | ✅ | How this variant is sold |
| Status | `CatalogueItemVariantStatus` | `Active`, `Inactive` or `OutOfStock` | ✅ | Whether the variant is on sale, paused, or out of stock |
| Is Archived | `Boolean` | Always `false` here, since only non-archived variants are listed | ✅ | Whether the variant is archived |

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - No non-archived item with that identifier: it does not exist, or it has been archived - The person does not belong to the organization - Unexpected error |

### 5. User Updates an Item

**Who can reach this step: an owner or admin of the organization.**

- The user changes a non-archived item's name, description or status.
- The user changes existing variants by their identifier, including moving a variant between `Active`, `Inactive` and `OutOfStock`.
- The user adds new variants. They are sent in a separate list from the variants being changed.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User changes a non-archived item's name, description or status | - The change is saved - Fields left out of the request are left as they were |
| 2. User clears the item's description, or a variant's price, SKU, label or description | - That value is removed; a variant whose price is cleared has no set price |
| 3. User changes an existing variant's price, selling unit, attributes or status, listing it with its identifier among the variants to change | - The change is saved for that variant only - Variants not mentioned are left exactly as they were |
| 4. User marks a variant `OutOfStock` or `Inactive`, and later `Active` again | - Each move is saved; any move among these three statuses is allowed |
| 5. User adds a new variant to a non-archived item, listing it, with no identifier, among the new variants | - Stored as `Active` and not archived - Stored with no set price if none is given |
| 6. User renames an item to a name another non-archived item already has | - Rejected as a conflict |
| 7. User gives a variant a SKU that another non-archived variant already has | - Rejected as a conflict |
| 8. User changes an item that has been archived, or does not exist | - Nothing happens, and it is reported as successful — the same gap as [Customer Book gap 2]({{ site.baseurl }}{% link epics/05-customer-book.md %}#2-changes-to-an-archived-customer-are-silently-discarded) |
| 9. Among the variants to change, user names one that does not exist, belongs to another item, or is archived | - The whole update is refused as a server error, and nothing is stored - One message covers all three causes; they are not told apart |
| 10. A member with the ordinary user role tries to make a change | - Rejected |

#### Requirements

1. Only non-archived items can be changed.
2. A field left out of the request keeps its current value. The item status can be set to `Active` or `Inactive`. The item description, and a variant's price, SKU, label and description, can be cleared. A variant's selling unit can be replaced but never removed.
3. When given, a variant's attributes replace all of its existing attributes.
4. A variant's status can be set only to `Active`, `Inactive` or `OutOfStock`.
5. A new variant needs a selling unit, and its price is optional, just like one added in [step 1](#1-user-adds-an-item).
6. A rename must leave the item name unique among non-archived items, and every SKU unique among non-archived variants.
7. Variants to change and variants to add are sent in two separate lists. Every variant to change is named by its identifier; a new variant has no identifier, since it does not exist yet.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Which item to change |
| Name | `String` | 1–255 characters, trimmed; unique among non-archived items | ❌ | Leave out to keep the current name |
| Description | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep the current description; can be cleared |
| Status | `CatalogueItemStatus` | `Active` or `Inactive` | ❌ | Leave out to keep the current status |
| Variants | `CatalogueItemVariantUpdate[]` | Empty by default; existing variants only | ❌ | Changes to existing variants; variants not listed stay as they are |
| New Variants | `CatalogueItemVariantInsert[]` | Empty by default; no upper limit | ❌ | Variants to add to the item |

**CatalogueItemVariantUpdate**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form; a non-archived variant of this item | ✅ | Which existing variant to change |
| SKU | `String` | Same as in **CatalogueItemVariantInsert** | ❌ | Leave out to keep; can be cleared |
| Label | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep; can be cleared |
| Description | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep; can be cleared |
| Attributes | `Map<String, String>` | Same as in **CatalogueItemVariantInsert** | ❌ | Leave out to keep; when given, replaces all attributes |
| Price | `CatalogueItemVariantPrice` | See **Price** in step 1 | ❌ | Leave out to keep the current price; can be cleared |
| Selling Unit | `CatalogueItemVariantSaleUnit` | See **CatalogueItemVariantSaleUnit** in step 1 | ❌ | Leave out to keep the current selling unit |
| Status | `CatalogueItemVariantStatus` | `Active`, `Inactive` or `OutOfStock` | ❌ | Leave out to keep the current status |

New variants use **CatalogueItemVariantInsert**, defined in step 1.

**Response**

Response is empty. A successful change answers with nothing but a success status, and so does a change that quietly did nothing.

**Outcome**

- For a non-archived item, the named fields and variants are changed, new variants are added as `Active` and not archived, and nothing else changes.
- For an archived or missing item nothing is stored, and the answer is the same as success.
- If anything is refused, nothing at all is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid, reported together with the variant each belongs to |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request - The price currency was not exactly `EUR`, `USD` or `GBP` in uppercase - A new variant is missing its required selling unit - A status value outside the item's or variant's status choices was sent |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - A non-archived item already has that name - A non-archived variant already has that SKU |
| 500 | `INTERNAL_SERVER_ERROR` | - A variant to change does not exist, belongs to another item, or is archived (one message for all three) - The person does not belong to the organization - Unexpected error |

### 6. User Archives an Item

**Who can reach this step: an owner or admin of the organization.**

- The user retires an item that is no longer for sale, along with all of its variants.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User archives a non-archived item | - The item's `isArchived` flag and every variant's flag become `true` - The item leaves the catalogue - Its name, and every SKU its variants held, become free for reuse - Its status, variants' statuses and image, if any, are kept |
| 2. User archives an item that is already archived, or does not exist | - Nothing happens, and it is reported as successful |
| 3. User wants an archived item back | - Not possible. There is no way to reverse archiving — the same gap as [Customer Book gap 3]({{ site.baseurl }}{% link epics/05-customer-book.md %}#3-archiving-is-final-and-archived-customers-cannot-be-found-again) |

#### Requirements

1. Archiving an item sets its `isArchived` flag and all of its variants' flags to `true`.
2. Archiving preserves the item's status and every variant's status. The item is kept in full, including its image and variants, but it can no longer be opened.
3. Archiving cannot be undone.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Which item to archive |

**Response**

Response is empty, whether the item was archived just now, was already archived, or never existed.

**Outcome**

- A non-archived item and all of its variants get `isArchived = true`. The item leaves the catalogue and can no longer be opened. Its status, the variants' statuses, and the record and image are kept. The name and the variants' SKUs become free for reuse.
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
| 1. User archives one of several non-archived variants | - That variant gets `isArchived = true` and its SKU becomes free for reuse - Its status, the item and the other variants are unchanged |
| 2. User archives the item's last non-archived variant | - Rejected as not allowed, and nothing changes - The item keeps its status; to retire it, archive the item as in [step 6](#6-user-archives-an-item) |
| 3. User archives a variant that is already archived, does not exist, or belongs to a different item from the one named | - Nothing happens, and it is reported as successful, the same as archiving an item |
| 4. User wants an archived variant back | - Not possible. Archiving cannot be undone |
| 5. A member with the ordinary user role tries to archive a variant | - Rejected |

#### Requirements

1. Archiving a variant sets its `isArchived` flag to `true` and preserves its status. The variant is kept, but it is no longer listed, counted, or priced anywhere in the catalogue.
2. A non-archived item always keeps at least one non-archived variant. Archiving a variant never archives its item.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | The item the variant belongs to |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | Which variant to archive |

**Response**

Response is empty, whether the variant was archived just now, was already archived, never existed, or belongs to a different item.

**Outcome**

- The variant gets `isArchived = true` and its SKU becomes free for reuse. Its status, the item and its other variants are unchanged.
- An already-archived or missing variant, or one belonging to a different item, is left exactly as it was.
- If it is the item's last non-archived variant, the request is refused and nothing changes: the variant and the item, including their statuses and archive flags, both stay as they were.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes - The variant is the item's last non-archived one; archive the item instead |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 8. User Looks Up Chosen Items

**Who can reach this step: any member of the organization.**

- This is the same summary operation as [browsing the catalogue](#3-user-browses-the-catalogue). The user may supply item identifiers to filter its response; leaving the identifiers absent or empty returns every non-archived item.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User asks for several non-archived items | - Each is returned with the same item and non-archived-variant summaries as in [step 3](#3-user-browses-the-catalogue) |
| 2. Some of the identifiers do not exist, belong to an archived item, or belong to another organization | - Those items are simply left out; the rest are returned - There is no error and no sign that anything was left out |
| 3. None of the identifiers match a non-archived item | - An empty list is returned |
| 4. User sends the same identifier more than once, or in any order | - Each item appears once - The list follows the order the items were added to the catalogue, oldest first, not the order of the request |
| 5. User sends no identifiers or an empty identifier list | - Every non-archived item is returned, in the same summary form as [step 3](#3-user-browses-the-catalogue) |

#### Requirements

1. Any member may look up items, whatever their role.
2. The identifiers are optional. When none are sent, every non-archived item of this organization is returned; when some are sent, only matching non-archived items are returned.
3. Returned items use the same item and non-archived-variant summaries as in [step 3](#3-user-browses-the-catalogue).

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item IDs | `UUID[]` | Zero or more entries; canonical 36-character form each; sent in the address as a repeated `catalogueItemIDs` key, such as `?catalogueItemIDs=a&catalogueItemIDs=b` | ❌ | Optional filter of which items to look up |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Items | `CatalogueItemSummary[]` | Every non-archived item when no IDs are sent; otherwise only the requested items that are non-archived in this organization; each at most once; oldest first; may be empty | ✅ | The catalogue or the requested items. See **CatalogueItemSummary** in step 3 |

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 9. User Checks a Name or SKU Is Free

**Who can reach this step: any member of the organization.**

- While filling in an item, for example as they type, the user checks whether a name or a SKU is still free before adding or changing anything.
- This lets them fix a clash early, instead of having the add or update refused as a conflict.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User checks both a name and a SKU | - An answer comes back for each |
| 2. User checks only a name, or only a SKU | - An answer comes back for that one only |
| 3. User checks neither | - Rejected as invalid, with separate errors for the missing name and SKU |
| 4. The name belongs to a non-archived item | - Reported as taken |
| 5. The SKU belongs to a non-archived variant, including an `Inactive` or `OutOfStock` one | - Reported as taken |
| 6. The name or SKU is not used anywhere in the organization | - Reported as free |
| 7. The name belongs only to an archived item, or the SKU only to an archived variant or a variant of an archived item | - Reported as free, the same way adding an item treats it |

#### Requirements

1. Any member may check, whatever their role.
2. At least one of a name and a SKU must be given.
3. A name counts as taken only if a non-archived item of this organization has it. A SKU counts as taken only if a non-archived variant of this organization has it. These are the same rules adding and updating an item apply.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Name | `String` | 1–255 characters, trimmed; sent in the address as `name` | ❌ | The item name to check. Give this, the SKU, or both |
| SKU | `String` | 1–255 characters, trimmed; case-sensitive; sent in the address as `sku` | ❌ | The variant SKU to check. Give this, the name, or both |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Is Name Unique | `Boolean` | Present only when a name was checked | ❌ | `true` if the name is free; `false` if a non-archived item already has it |
| Is SKU Unique | `Boolean` | Present only when a SKU was checked | ❌ | `true` if the SKU is free; `false` if a non-archived variant already has it |

**Outcome**

Nothing changes, and nothing is reserved. A name or SKU reported free can still be taken before the user adds or changes the item, and that request is then refused as a conflict as usual.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Neither a name nor a SKU was given; both fields are named in the errors - A name or SKU was given but is invalid, such as blank |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### Known gaps and open questions

Everything above describes the agreed product. Nothing in this section has been decided yet, and each item needs a product answer before it can be built.

#### 1. Uploading for a missing or archived item is reported as a server error

Naming an item that does not exist, belongs to another organization, or has been archived is refused as a server error. Picking an item a colleague just archived, or following a stale link, is ordinary, not a failure on our side.

**To decide:** whether this should be a plain refusal instead, naming what was wrong with the item.

{% include abbreviations.md %}
