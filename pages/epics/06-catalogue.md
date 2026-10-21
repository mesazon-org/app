---
title: Catalogue
---

# Catalogue

### Overview

The catalogue records what an organization sells. Each item has one or more variants, and each variant describes how it is sold, its optional price, and the details that distinguish it. People can add, browse, open, update, archive, and check items, and can upload an image for an item. Catalogue extraction from an image or file is planned but not built yet.

### Related / Out of scope

- **Related** — [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}). An organization must exist before its catalogue can be used.
- **Related** — [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}). It uses the same organization membership and role rules for a different kind of record.
- **Not built yet** — Orders. Nothing places an order or decides whether an item without a price can be ordered.
- **Not built yet** — Search, filtering, and paging. Catalogue browsing returns all non-archived items.
- **Not built yet** — Time-based pricing and images for individual variants.
- **Not built yet** — Extracting catalogue items and variants from an image, CSV file, or Excel file for review before adding them.

### Requirements across the epic

#### Functional

1. An item has a name, an optional description, and at least one variant. A variant has a required selling unit and may have a price, SKU, label, description, and attributes.
2. A price belongs to a variant and covers exactly the quantity described by that variant's selling unit. Variants without a price remain listed and can be opened.
3. An item name is unique among the organization's non-archived items. A SKU, when present, is unique among the organization's non-archived variants across all items. SKU comparison ignores letter case, while the submitted spelling is preserved.
4. Items and variants are archived rather than deleted. Archiving an item archives all of its variants. A variant cannot be archived when it is the item's last non-archived variant.
5. Business status and archive state are separate. An item is `Active` or `Inactive`; a variant is `Active`, `Inactive`, or `OutOfStock`. Every non-archived status remains visible, editable, and eligible for uniqueness checks and image upload.
6. Public reads return non-archived records only. They do not include an archive flag.
7. An item may have one image. A later upload replaces the existing image.

#### Non-functional

1. Every request identifies an organization. The person must be signed in, have completed personal onboarding, and belong to that organization.
2. Owners, admins, and ordinary members can read. Only owners and admins can add, update, archive, or upload an image.
3. Price amounts use exactly two decimal places after validation without rounding. Selling-unit validation preserves the supplied decimal value, but very precise HTTP inputs can be rounded before validation; see [gap 11](#11-very-precise-input-numbers-can-be-rounded).
4. A price currency is exactly the uppercase `EUR`, `USD`, or `GBP` code. Invalid currency values are rejected before field validation.
5. When several validation problems are present, all are returned together in a deterministic order.
6. A batch add is all-or-nothing. If any item fails, none of the items is stored.
7. Missing, invalid, or expired access tokens use the product's standard unauthorized response.

### User flow

These are separate operations on a catalogue rather than one required journey.

1. [User Adds an Item](#1-user-adds-an-item)
2. [User Uploads an Item's Image](#2-user-uploads-an-items-image)
3. [User Browses the Catalogue](#3-user-browses-the-catalogue)
4. [User Opens an Item](#4-user-opens-an-item)
5. [User Updates an Item](#5-user-updates-an-item)
6. [User Archives an Item](#6-user-archives-an-item)
7. [User Archives a Variant](#7-user-archives-a-variant)
8. [User Looks Up Chosen Items](#8-user-looks-up-chosen-items)
9. [User Checks a Name or SKU Is Free](#9-user-checks-a-name-or-sku-is-free)
10. [User Extracts Catalogue Items from an Image or File](#10-user-extracts-catalogue-items-from-an-image-or-file)

### Prerequisites

**Item status** — an item starts `Active` and may be changed to `Inactive` while it is non-archived. `Inactive` does not hide or disable the item.

**Variant status** — a new variant starts `Active`. An existing variant may be moved between `Active`, `Inactive`, and `OutOfStock` while it is non-archived. These statuses do not hide or disable the variant.

**Archive state** — new items and variants are non-archived. Only the archive operations set the archive state. Archiving is permanent, and an archive operation preserves the item's and variants' business statuses and other details.

**Selling unit** — every variant has a quantity and unit. It may also have a per-unit part and a unit-size part. Each number is greater than 0 and at most 99,999. Validation adds no decimal-place limit; the current input-decoding limitation is described in [gap 11](#11-very-precise-input-numbers-can-be-rounded).

| Part | Meaning | Required |
| --- | --- | --- |
| Quantity | The amount covered by one sale | ✅ |
| Per unit | How many containers or pieces are in the sold unit | ❌ |
| Unit size | The size of each individual thing | ❌ |

- Quantity may use a measurement, package, or per-unit unit.
- Per unit may use `Bag`, `Bottle`, `Can`, `Jar`, or `Piece`.
- Unit size may use `Gram`, `Kilogram`, `Millilitre`, `Litre`, `Millimetre`, `Centimetre`, `Metre`, or `SquareMetre`.
- Package units are `Pack` and `Box`.

**Price** — a price is an exact non-negative amount and a currency together. The amount has at most 12 integer digits and at most two decimal places on input. Lower scales are padded to two places; values are never rounded. `EUR`, `USD`, and `GBP` are the available currencies.

### 1. User Adds an Item

**Who can reach this step: an owner or admin of the organization.**

- The user supplies an item name, an optional description, and one or more variants.
- A batch request can add several items together.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User adds an item with one or several variants | - The item and all variants are stored - Every new item and variant starts `Active` and non-archived |
| 2. A variant has no price | - It is stored and listed without a set price |
| 3. Variants use different quantities, units, prices, or currencies | - Each variant keeps its own values |
| 4. User adds an item without a variant | - The request is rejected |
| 5. A name or SKU is already reserved by a non-archived record | - The request is rejected as a conflict |
| 6. Several validation problems occur in one request | - Every problem is returned together in a deterministic order |
| 7. A batch contains no items or one item fails | - The request is rejected and no item is stored |
| 8. A member without an owner or admin role tries to add an item | - The request is rejected |

#### Requirements

1. An item needs a name and at least one variant. The description is optional.
2. Every variant needs a selling unit. Its price and other variant fields are optional.
3. Names, prices, selling units, SKUs, and attributes follow the rules in this epic.
4. A batch must contain at least one item and succeeds only when every item is valid.

#### Request / Response / Outcome

**Request — adding one item**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Name | `String` | 1–255 characters, trimmed; unique among non-archived items | ✅ | The item's name |
| Description | `String` | 1–255 characters, trimmed | ❌ | A longer description of the item |
| Variants | `CatalogueItemVariantInsert[]` | At least one entry; up to 1,024 entries can be decoded | ❌ | The versions of the item that are sold |

**CatalogueItemVariantInsert**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| SKU | `String` | 1–255 characters, trimmed; case-insensitive uniqueness among non-archived variants | ❌ | The business's stock code; its submitted spelling is preserved |
| Label | `String` | 1–255 characters, trimmed | ❌ | A short label for the variant |
| Description | `String` | 1–255 characters, trimmed | ❌ | A longer description of the variant |
| Attributes | `Map<String, String>` | Empty by default; up to 1,024 entries can be decoded; unique names; each name and value is 1–255 characters and trimmed | ❌ | What sets this variant apart |
| Price | `CatalogueItemVariantPrice` | See **Price** | ❌ | The full price of the selling unit |
| Selling Unit | `CatalogueItemVariantSaleUnit` | See **Selling unit** | ✅ | How the variant is sold |

**CatalogueItemVariantPrice**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Amount | `BigDecimal` | Non-negative; at most 12 integer digits; 0–2 decimal places on input; stored with exactly 2 decimal places | ✅ | The exact amount |
| Currency | `Currency` | `EUR`, `USD`, or `GBP` in exact uppercase form | ✅ | The currency of the amount |

**CatalogueItemVariantSaleUnit**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Quantity | `CatalogueItemVariantQuantity` | Positive and at most 99,999 | ✅ | The amount covered by one sale |
| Per Unit | `CatalogueItemVariantPerUnit` | Positive and at most 99,999 | ❌ | The containers or pieces in the sold unit |
| Unit Size | `CatalogueItemVariantUnitSize` | Positive and at most 99,999 | ❌ | The size of each individual thing |

**Request — adding several items**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Items | `CatalogueItemPostRequest[]` | At least one entry; up to 1,024 entries can be decoded | ❌ | The items to add together |

**Response — adding one item**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Identifies the new item |

**Response — adding several items**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item IDs | `UUID[]` | One canonical UUID per item, in request order | ✅ | Identifies each new item |

Only item identifiers are returned. Variant identifiers are available when the item is opened.

**Outcome**

- A successful single request stores the item and all of its variants.
- A successful batch stores every item in request order, or stores none of them.
- A rejected request stores nothing.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | A batch is empty, or one or more fields are invalid; all validation problems are returned together |
| 400 | `BAD_REQUEST_ERROR` | The organization is missing, a required selling unit is missing, or the currency is not an exact supported code |
| 401 | `UNAUTHORIZED_ERROR` | The access token is missing, invalid, or expired |
| 403 | `FORBIDDEN_ERROR` | Personal onboarding is incomplete or the person's role cannot add items |
| 409 | `CONFLICT_ERROR` | A non-archived item already has the name, or a non-archived variant already has the SKU |
| 500 | `INTERNAL_SERVER_ERROR` | The person does not belong to the organization, or an unexpected error occurs |

### 2. User Uploads an Item's Image

**Who can reach this step: an owner or admin of the organization.**

- The user uploads an image for a non-archived item.
- A later upload replaces the item's existing image.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User uploads a valid image | - The original and a smaller standard copy are stored |
| 2. User uploads another image | - The new image replaces the previous image |
| 3. The file is not a supported image or its name does not match its contents | - The request is rejected and the image is not stored |
| 4. The item is missing, archived, or belongs to another organization | - The request is rejected and the image is not stored |
| 5. A member without an owner or admin role tries to upload | - The request is rejected |

#### Requirements

1. PNG, JPEG, and WEBP files are accepted when the filename extension agrees with the file's contents.
2. An image may be up to 20 MB.
3. The smaller copy is at most 640×640 pixels.
4. Only a non-archived item can receive an image. Its item and variant statuses are unchanged by the upload.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form | ✅ | The organization that owns the item |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | The item receiving the image |
| File Name | `String` | 1–255 characters, trimmed; supported extension matching the image | ✅ | The original file name |
| Image | `Binary` | PNG, JPEG, or WEBP; up to 20 MB | ✅ | The image contents |

**Response**

Response is empty.

**Outcome**

- The original image and smaller copy are stored for the item.
- Uploading again replaces both copies.
- A refusal before image storage begins leaves the item and its stored image unchanged. Failures during storage have the limitations in [gap 9](#9-image-changes-are-not-atomic).

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | A required request value is missing, the filename is unsupported, or the filename and image contents disagree |
| 401 | `UNAUTHORIZED_ERROR` | The access token is missing, invalid, or expired |
| 403 | `FORBIDDEN_ERROR` | Personal onboarding is incomplete or the person's role cannot upload images |
| 500 | `INTERNAL_SERVER_ERROR` | The person does not belong to the organization, the item cannot be found or is archived, the image cannot be processed, or an unexpected error occurs |

### 3. User Browses the Catalogue

**Who can reach this step: any member of the organization.**

- The user sees every non-archived item and its non-archived variants.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. The organization has non-archived items | - Every one is returned with its status, image if present, and variant summaries |
| 2. An item or variant is `Inactive` or `OutOfStock` | - It remains visible with that status |
| 3. A variant is archived | - It is omitted from the item |
| 4. An item has no image or the organization has no items | - The image is absent, or an empty list is returned |

#### Requirements

1. Any organization member may browse the catalogue.
2. Only non-archived items and variants are returned.
3. Variant summaries include their optional price and SKU, required selling unit and status.
4. Items and variants are returned in catalogue order.

#### Request / Response / Outcome

**Request**

The request has no catalogue fields; it identifies the organization.

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Summaries | `CatalogueItemSummary[]` | Every non-archived item, oldest first; may be empty | ✅ | The catalogue |

**CatalogueItemSummary**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Identifies the item |
| Name | `String` | — | ✅ | The item's name |
| Status | `CatalogueItemStatus` | `Active` or `Inactive` | ✅ | The item's status |
| Image URL | `String` | Present only when the item has an image | ❌ | A link to the item's image |
| Variants | `CatalogueItemVariantSummary[]` | Every non-archived variant, oldest first; at least one | ✅ | The item's variants |

**CatalogueItemVariantSummary**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | Identifies the variant |
| SKU | `String` | 1–255 characters, trimmed; case-insensitive uniqueness | ❌ | The variant's stock code |
| Label | `String` | 1–255 characters, trimmed | ❌ | A short label for the variant |
| Price | `CatalogueItemVariantPrice` | See **CatalogueItemVariantPrice** | ❌ | The variant's price |
| Selling Unit | `CatalogueItemVariantSaleUnit` | See **CatalogueItemVariantSaleUnit** | ✅ | How the variant is sold |
| Status | `CatalogueItemVariantStatus` | `Active`, `Inactive`, or `OutOfStock` | ✅ | The variant's status |

**Outcome**

Nothing changes.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | The organization is missing |
| 401 | `UNAUTHORIZED_ERROR` | The access token is missing, invalid, or expired |
| 403 | `FORBIDDEN_ERROR` | Personal onboarding is incomplete |
| 500 | `INTERNAL_SERVER_ERROR` | The person does not belong to the organization, or an unexpected error occurs |

### 4. User Opens an Item

**Who can reach this step: any member of the organization.**

- The user opens one non-archived item to see its full details and variants.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. The item is non-archived | - Its details and every non-archived variant are returned |
| 2. Some variants are archived | - The item opens normally and those variants are omitted |
| 3. The item is missing, archived, or belongs to another organization | - The request is rejected as a server error |

#### Requirements

1. The item is selected by identifier.
2. Only a non-archived item can be opened.
3. The response includes the item's status and each returned variant's status.
4. Returned variants include their complete attributes, price, selling unit, and optional descriptive fields.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | The item to open |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | Identifies the item |
| Name | `String` | — | ✅ | The item's name |
| Description | `String` | Present only when set | ❌ | The item's description |
| Status | `CatalogueItemStatus` | `Active` or `Inactive` | ✅ | The item's status |
| Image URL | `String` | Present only when an image exists | ❌ | A link to the item's image |
| Variants | `CatalogueItemVariant[]` | Every non-archived variant; at least one | ✅ | The item's variants |

**CatalogueItemVariant**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | Identifies the variant |
| SKU | `String` | Present only when set | ❌ | The variant's stock code |
| Label | `String` | Present only when set | ❌ | A short label |
| Description | `String` | Present only when set | ❌ | A longer description |
| Attributes | `Map<String, String>` | May be empty | ✅ | The variant's distinguishing details |
| Price | `CatalogueItemVariantPrice` | Present only when set | ❌ | The variant's price |
| Selling Unit | `CatalogueItemVariantSaleUnit` | Required | ✅ | How the variant is sold |
| Status | `CatalogueItemVariantStatus` | `Active`, `Inactive`, or `OutOfStock` | ✅ | The variant's status |

**Outcome**

Nothing changes.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | The organization or item identifier is missing or malformed |
| 401 | `UNAUTHORIZED_ERROR` | The access token is missing, invalid, or expired |
| 403 | `FORBIDDEN_ERROR` | Personal onboarding is incomplete |
| 500 | `INTERNAL_SERVER_ERROR` | The item does not exist, is archived, belongs to another organization, or an unexpected error occurs |

### 5. User Updates an Item

**Who can reach this step: an owner or admin of the organization.**

- The user changes item fields, changes existing variants by identifier, or adds new variants.
- Item and variant business status can be changed while the records remain non-archived.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User changes an item's name, description, or status | - The change is saved; omitted fields remain unchanged |
| 2. User clears an optional item or variant field | - The selected value is removed |
| 3. User replaces an existing variant field or attributes | - The change applies to that variant; unmentioned variants remain unchanged |
| 4. User adds a new variant | - It is stored as `Active` and non-archived |
| 5. An item is missing or archived | - The update succeeds without changing anything |
| 6. A named variant is missing, archived, or belongs to another item | - The update is rejected and no changes are stored |
| 7. A new name or SKU is already reserved | - The update is rejected as a conflict and no changes are stored |
| 8. A member without an owner or admin role tries to update | - The request is rejected |

#### Requirements

1. Fields left out of the request keep their current values. Item description and variant SKU, label, description, and price can be cleared explicitly.
2. Item status can be `Active` or `Inactive`. Variant status can be `Active`, `Inactive`, or `OutOfStock`.
3. A supplied attributes map replaces all existing attributes, and an empty map clears them. A supplied selling unit replaces the existing selling unit; a selling unit cannot be removed.
4. New variants use the same fields and rules as inserted variants and start `Active` and non-archived.
5. General updates cannot archive records. Only the archive operations set archive state.
6. Names and SKUs remain unique among non-archived records.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | The item to update |
| Name | `String` | 1–255 characters, trimmed; unique among non-archived items | ❌ | A replacement name |
| Description | `String` | 1–255 characters, trimmed; explicit null clears it | ❌ | A replacement description |
| Status | `CatalogueItemStatus` | `Active` or `Inactive` | ❌ | A replacement item status |
| Variants Update | `CatalogueItemVariantUpdate[]` | Existing variants; empty by default; up to 1,024 entries can be decoded | ❌ | Changes to existing variants |
| Variants Insert | `CatalogueItemVariantInsert[]` | New variants; empty by default; up to 1,024 entries can be decoded | ❌ | Variants to add |

**CatalogueItemVariantUpdate**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | The existing variant to update |
| SKU | `String` | Same rules as **CatalogueItemVariantInsert**; explicit null clears it | ❌ | A replacement stock code |
| Label | `String` | 1–255 characters, trimmed; explicit null clears it | ❌ | A replacement label |
| Description | `String` | 1–255 characters, trimmed; explicit null clears it | ❌ | A replacement description |
| Attributes | `Map<String, String>` | Same rules as **CatalogueItemVariantInsert** | ❌ | Replacement attributes |
| Price | `CatalogueItemVariantPrice` | Same rules as **CatalogueItemVariantInsert**; explicit null clears it | ❌ | A replacement price |
| Selling Unit | `CatalogueItemVariantSaleUnit` | Same rules as **CatalogueItemVariantInsert** | ❌ | A replacement selling unit |
| Status | `CatalogueItemVariantStatus` | `Active`, `Inactive`, or `OutOfStock` | ❌ | A replacement variant status |

New variants use **CatalogueItemVariantInsert**. A request field left out keeps its value; an explicit nullable value clears that field.

**Response**

Response is empty.

**Outcome**

- A non-archived item's requested changes are applied atomically.
- A missing or archived item is unchanged and still returns success.
- Invalid referenced variants and conflicts leave the item and all variants unchanged.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | One or more item or variant fields are invalid; all such problems are returned together |
| 400 | `BAD_REQUEST_ERROR` | The organization is missing, a required selling unit is missing, or a currency or status value is invalid |
| 401 | `UNAUTHORIZED_ERROR` | The access token is missing, invalid, or expired |
| 403 | `FORBIDDEN_ERROR` | Personal onboarding is incomplete or the person's role cannot update items |
| 409 | `CONFLICT_ERROR` | A non-archived item already has the name, or a non-archived variant already has the SKU |
| 500 | `INTERNAL_SERVER_ERROR` | A referenced variant is missing, archived, belongs to another item, or an unexpected error occurs |

### 6. User Archives an Item

**Who can reach this step: an owner or admin of the organization.**

- The user retires an item and all of its variants.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User archives a non-archived item | - The item and all variants leave the catalogue - Their statuses, details, and image are retained - The name and SKUs become free |
| 2. User archives an item that is already archived or does not exist | - Nothing changes and the request succeeds |
| 3. User wants an archived item back | - Archiving cannot be undone |

#### Requirements

1. Archiving an item archives all of its variants.
2. Archiving preserves business statuses and stored details.
3. Archiving cannot be undone.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | The item to archive |

**Response**

Response is empty.

**Outcome**

The item and its variants are no longer returned by catalogue reads, while their statuses and stored details remain retained. An already archived or missing item is unchanged.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | The organization or item identifier is missing or malformed |
| 401 | `UNAUTHORIZED_ERROR` | The access token is missing, invalid, or expired |
| 403 | `FORBIDDEN_ERROR` | Personal onboarding is incomplete or the person's role cannot archive items |
| 500 | `INTERNAL_SERVER_ERROR` | The person does not belong to the organization, or an unexpected error occurs |

### 7. User Archives a Variant

**Who can reach this step: an owner or admin of the organization.**

- The user retires one variant while leaving the item and its other variants available.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User archives one of several non-archived variants | - That variant leaves catalogue reads and its SKU becomes free - The item, other variants, and statuses are unchanged |
| 2. User archives the item's last non-archived variant | - The request is rejected and nothing changes - The user must archive the item instead |
| 3. The variant is already archived, missing, or belongs to another item | - Nothing changes and the request succeeds |
| 4. User wants an archived variant back | - Archiving cannot be undone |

#### Requirements

1. A variant archive preserves the variant's status and stored details.
2. A non-archived item always has at least one non-archived variant.
3. Archiving a variant never archives its item.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item ID | `UUID` | Canonical 36-character form | ✅ | The item containing the variant |
| Catalogue Item Variant ID | `UUID` | Canonical 36-character form | ✅ | The variant to archive |

**Response**

Response is empty.

**Outcome**

The selected variant is omitted from catalogue reads and its SKU becomes free. If it is the last non-archived variant, the item and variant remain unchanged.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | The organization or identifier is missing or malformed |
| 401 | `UNAUTHORIZED_ERROR` | The access token is missing, invalid, or expired |
| 403 | `FORBIDDEN_ERROR` | Personal onboarding is incomplete, the person's role cannot archive variants, or the variant is the item's last non-archived variant |
| 500 | `INTERNAL_SERVER_ERROR` | The person does not belong to the organization, or an unexpected error occurs |

### 8. User Looks Up Chosen Items

**Who can reach this step: any member of the organization.**

- The user may provide item identifiers to filter the same summaries returned by browsing.
- No identifiers, or an empty list, returns every non-archived item.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User supplies matching item identifiers | - Each matching non-archived item is returned once with its summaries |
| 2. An identifier is missing, archived, or belongs to another organization | - That item is omitted without an error |
| 3. No supplied identifier matches | - An empty list is returned |
| 4. User supplies duplicate identifiers or a different request order | - Each item appears once in catalogue order |
| 5. User supplies no identifiers or an empty list | - Every non-archived item is returned |

#### Requirements

1. Any organization member may use this lookup.
2. The identifier filter is optional and repeated identifiers do not duplicate results.
3. Results use the same non-archived item and variant summaries as browsing.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item IDs | `UUID[]` | Zero or more canonical UUIDs; optional repeated values | ❌ | Items to include; absent or empty means all |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Catalogue Item Summaries | `CatalogueItemSummary[]` | Matching non-archived items, once each, oldest first; may be empty | ✅ | The selected catalogue items |

**Outcome**

Nothing changes.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | The organization or an identifier is missing or malformed |
| 401 | `UNAUTHORIZED_ERROR` | The access token is missing, invalid, or expired |
| 403 | `FORBIDDEN_ERROR` | Personal onboarding is incomplete |
| 500 | `INTERNAL_SERVER_ERROR` | The person does not belong to the organization, or an unexpected error occurs |

### 9. User Checks a Name or SKU Is Free

**Who can reach this step: any member of the organization.**

- Before adding or updating an item, the user may check whether its name or a variant SKU is available.
- The check reserves nothing; a later add or update can still lose a race and return a conflict.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User checks a name, a SKU, or both | - The response contains the corresponding availability result |
| 2. Neither value is supplied, or a supplied value is blank or invalid | - The request is rejected with the relevant validation fields |
| 3. A non-archived item has the name | - The name is reported as taken |
| 4. A non-archived variant has the SKU | - The SKU is reported as taken, ignoring letter case |
| 5. Only archived records use the name or SKU | - The value is reported as free |

#### Requirements

1. Any organization member may check availability.
2. At least one of name and SKU must be supplied. When neither is supplied, both fields are named in the validation response.
3. Name and SKU checks use the same non-archived scope and case-insensitive SKU rule as add and update.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Name | `String` | 1–255 characters, trimmed | ❌ | The item name to check |
| SKU | `String` | 1–255 characters, trimmed; comparison ignores letter case | ❌ | The variant SKU to check |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Is Name Unique | `Boolean` | Present only when a name was supplied | ❌ | Whether the name is free |
| Is SKU Unique | `Boolean` | Present only when a SKU was supplied | ❌ | Whether the SKU is free |

**Outcome**

Nothing changes and no name or SKU is reserved.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Neither field was supplied, or a supplied name or SKU is invalid; both fields are named when neither is supplied |
| 400 | `BAD_REQUEST_ERROR` | The organization is missing |
| 401 | `UNAUTHORIZED_ERROR` | The access token is missing, invalid, or expired |
| 403 | `FORBIDDEN_ERROR` | Personal onboarding is incomplete |
| 500 | `INTERNAL_SERVER_ERROR` | The person does not belong to the organization, or an unexpected error occurs |

### 10. User Extracts Catalogue Items from an Image or File

**Status: Not built yet.**

**Who can reach this step: an owner or admin of the organization.**

- The user has catalogue information in a product photo, CSV file, or Excel file and wants a quick starting point for the catalogue.
- An AI model reads the image or file and returns catalogue item candidates for review.
- The user resolves anything marked for attention before sending a candidate through the normal item-add flow.
- Nothing is stored by extraction, and an uploaded photo is not attached to an item.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User sends an image, CSV file, or Excel file with recognizable catalogue information | - The response contains candidate items with their variants and any readable details |
| 2. An item has a readable name but some required or uncertain information is missing | - The candidate is returned with `needsAttention: true` and notes explaining what needs review |
| 3. No item name can be read for an entry and it has no clear association with a named item | - The entry is not returned as a candidate and is counted as unidentified; spreadsheet row numbers are listed |
| 4. The source contains several rows or visible entries that belong to one item | - The AI may group them into one candidate with multiple variants - `needsAttention` is true when the grouping is uncertain |
| 5. A row has no item name but is clearly associated with a named item | - It becomes a variant of that item - It counts as an identified source entry, not as a separate processed item, and is not listed as unidentified |
| 6. A meaningful source column does not map to a standard item or variant field | - Its value is preserved as a variant attribute |
| 7. A price has an unsupported or unclear currency | - The price is omitted, the candidate is marked for attention, and the issue is explained in its notes |
| 8. A required selling-unit value or structured unit cannot be read | - The candidate is returned for review with `needsAttention: true` |
| 9. Two candidates have item names that match when letter case is ignored | - Both are marked as possible duplicates - They are not merged - Duplicate marking alone does not require attention |
| 10. The image or file has no recognizable catalogue information | - Empty candidate lists are returned, with both identified and processed counts at zero - The notes explain that nothing usable was detected |
| 11. A spreadsheet row is completely blank | - It is not counted as an entry and its row number is listed as empty |
| 12. A non-blank spreadsheet row has no readable item name or clear association with a named item | - It is not returned as a candidate and its row number is listed as unidentified |
| 13. The file name is missing, malformed, unsupported, or does not match the content | - The request is rejected before the file is sent to the AI |
| 14. The file cannot be read as a CSV or Excel file, or is larger than 20 MB | - The request fails and no candidate result is returned |
| 15. The AI service has a temporary failure | - The service tries again up to two more times - If every attempt fails, the request fails with no partial result |
| 16. The AI service rejects the request or sends back an unusable answer | - The request fails without retrying that response |
| 17. A member without an owner or admin role tries this step | - The request is rejected |
| 18. The user reads the same image or file again | - The read is allowed and nothing from the previous read is reused or stored |
| 19. The user sends an Excel workbook with several sheets | - Only the first sheet is read |
| 20. The user sends a legacy `.xls` file | - It is read in the same way as an `.xlsx` file |
| 21. The user sends a spreadsheet with many rows | - Every row is read in groups of up to 50, with up to 3 groups at a time - If any group fails, no partial result is returned |
| 22. The user sends a supported file whose extension uses different letter case | - It is accepted when the extension matches the actual content |
| 23. A candidate has `needsAttention: true` | - The client does not allow it to be sent through the normal item-add request until the user resolves the issue |
| 24. The user supplies an extraction message | - The message guides optional field or layout interpretation across the whole upload and every spreadsheet batch |
| 25. The message is empty, whitespace-only, ambiguous, or conflicts with the source | - Empty messages use the default rules - Ambiguous instructions produce the best result with unresolved issues in attention flags or notes - The request is not rejected solely for ambiguity |
| 26. The extraction message is longer than 2,000 characters after trimming | - The request is rejected as a validation error |

#### Requirements

1. PNG, JPEG, and WEBP images, and genuinely readable CSV, `.xls`, and `.xlsx` files, are accepted up to 20 MB. Spreadsheet row count is not capped.
2. The file name is required, 1–255 trimmed characters, and must have a supported extension matching the actual content, ignoring letter case.
3. The response contains candidate catalogue items, empty spreadsheet row numbers, unidentified spreadsheet row numbers, and a concise note when there is anything else to review. Image responses always have empty row-number lists.
4. A candidate contains an item name when one can be read, an optional description, zero or more extracted variants, duplicate status, attention status, and extraction notes. A candidate may omit fields that need user completion.
5. A variant candidate may contain an optional SKU, label, description, price, selling unit, and attributes. Meaningful unmapped source columns become attributes. Structured unit values are preserved as stated and are not converted or invented.
6. A candidate's `needsAttention` is true when a required field, a value, or an item/variant grouping is missing or uncertain, including when a price currency is unclear or unsupported. It is false when the candidate is resolved enough to send to the normal item-add request.
7. Duplicate marking compares candidate item names within the same response, ignoring letter case. Duplicates are flagged but never merged, and candidates are never compared with items already in the catalogue.
8. `entriesIdentified` describes the entries recognized in the source, including variant-only rows clearly associated with a named item, while `entriesProcessed` counts resulting candidate items after any grouping. A clearly associated variant-only row does not increase `entriesProcessed`. Blank rows are excluded from both counts; rows without a name or clear association are reported separately.
9. Nothing is stored: no item, variant, image, file, or extraction response. The normal item-add request remains the only way to create catalogue records, and its existing validation remains unchanged.
10. Each AI attempt may take up to one minute. Temporary failures are tried again up to two more times; rejected requests and unusable answers are not retried.
11. Large spreadsheets are read in groups of up to 50 rows, with up to 3 groups at a time. Any failed group fails the whole read and returns no partial candidates.
12. An optional processing message is trimmed plain text up to 2,000 characters. An empty or whitespace-only message is treated as absent.
13. The processing message may guide optional field selection and source layout interpretation, but cannot override candidate requirements, selling-unit rules, row counting, duplicate marking, or normal add-item validation. An instruction to omit an optional price leaves it absent and does not alone set `needsAttention`.
14. One processing message applies to the whole upload and every spreadsheet batch. It is used only for that extraction request and is never stored or returned as catalogue data.

#### Request / Response / Outcome

One upload carries whichever source the user has — an image, a CSV file, or an Excel file — in the same request shape.

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form | ✅ | The organization whose catalogue is being prepared |
| File | `Binary` | PNG, JPEG or WEBP; or genuinely readable as CSV or Excel (`.xls` or `.xlsx`); up to 20 MB | ✅ | The image or file to read |
| File Name | `String` | 1–255 characters, trimmed; supported extension matching the actual content | ✅ | The source file name; it is not stored |
| Processing Message | `String` | Up to 2,000 characters, trimmed plain text | ❌ | Optional guidance for interpreting the uploaded source |

**Response — `ExtractCatalogueItemsPostResponse`**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Entries Identified | `Long` | Whole number, zero or more | ✅ | Entries recognized in the source before candidate grouping |
| Entries Processed | `Long` | Whole number, zero or more | ✅ | Candidate items returned after grouping |
| Catalogue Item Candidates | `ExtractCatalogueItemData[]` | May be empty | ✅ | Item candidates for review |
| Empty Entry Rows | `Long[]` | May be empty; always empty for an image | ✅ | Spreadsheet rows that were completely blank |
| Unidentified Entry Rows | `Long[]` | May be empty; always empty for an image | ✅ | Spreadsheet rows with content but no readable item name or clear association with a named item |
| Unidentified Entries Notes | `String` | Concise, plain text | ❌ | Anything else worth flagging, including when no catalogue information could be detected |

**ExtractCatalogueItemData**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Candidate | `ExtractCatalogueItemCandidate` | The item name is present; other fields may need completion | ✅ | The extracted item and its variants |
| Is Duplicate | `Boolean` | — | ✅ | True when another candidate in this response has the same item name, ignoring letter case |
| Needs Attention | `Boolean` | — | ✅ | True when the candidate has a missing or uncertain value or grouping and cannot yet be sent through the normal item-add request |
| Extraction Notes | `String` | Concise, plain text | ❌ | What the user should review before adding the item |

**ExtractCatalogueItemCandidate**

The candidate contains the same item fields as an item-add request, but fields that need user completion may be absent.

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Name | `String` | 1–255 characters, trimmed | ✅ | The extracted item name |
| Description | `String` | 1–255 characters, trimmed | ❌ | The extracted item description |
| Variants | `ExtractCatalogueItemVariantCandidate[]` | May be empty while the candidate needs attention | ✅ | The extracted versions of the item |

**ExtractCatalogueItemVariantCandidate**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| SKU | `String` | 1–255 characters, trimmed | ❌ | The extracted stock code |
| Label | `String` | 1–255 characters, trimmed | ❌ | The extracted variant label |
| Description | `String` | 1–255 characters, trimmed | ❌ | The extracted variant description |
| Attributes | `Map<String, String>` | May be empty; names and values are 1–255 characters and trimmed | ✅ | Structured or unmapped details that distinguish the variant |
| Price | `CatalogueItemVariantPrice` | Present only when the amount and supported currency are resolved | ❌ | The extracted price |
| Selling Unit | `CatalogueItemVariantSaleUnit` | May be absent until required values are resolved | ❌ | How the variant is sold |

**Outcome**

- The user receives a preview of catalogue item candidates and the issues that need attention.
- A candidate with `needsAttention: true` cannot be sent by the client through the normal item-add request until it is resolved.
- Nothing is stored, and an uploaded image is not attached to an item.
- The user adds resolved candidates through [step 1](#1-user-adds-an-item), where the existing validation and duplicate checks still apply.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | The processing message is longer than 2,000 characters after trimming |
| 400 | `BAD_REQUEST_ERROR` | The organization is missing, the file name is missing or malformed, or the file name and content do not agree |
| 401 | `UNAUTHORIZED_ERROR` | The access token is missing, invalid, or expired |
| 403 | `FORBIDDEN_ERROR` | Personal onboarding is incomplete or the person's role does not allow extraction |
| 500 | `INTERNAL_SERVER_ERROR` | The upload cannot be read, is larger than 20 MB, is not genuinely readable as a CSV or Excel file, the person does not belong to the organization, the AI service fails, or an unexpected error occurs |

### Known gaps and open questions

#### 1. Uploading for a missing or archived item is reported as a server error

Naming an item that does not exist, belongs to another organization, or has been archived returns a server error. A stale item reference is an ordinary client-side condition, but the current response does not distinguish it from an unexpected server failure.

**To decide:** whether this should return a client error that identifies the invalid item reference.

#### 2. Oversized image uploads may not return promptly

The product limit is 20 MB, but the current streaming transport can stall on an oversized request instead of returning a clear refusal.

**To decide:** which client error and response behavior should be guaranteed for an upload over the limit.

#### 3. A request from outside the organization is reported as a server error

Naming an organization that the signed-in person does not belong to returns a server error. This is the same unresolved organization-membership behavior described in [Organization Onboarding gap 3]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#3-not-belonging-to-an-organization-is-reported-as-a-server-error).

**To decide:** whether a non-member should receive the same refusal as a member without the required role, or a response that identifies the organization access problem.

#### 4. Very large valid requests can fail during persistence

Each incoming JSON list or attribute map is limited to 1,024 entries by request decoding. A batch of individually valid items can still contain enough total variants to fail with a server error, with nothing saved. These implementation limits are not explicit product validation rules.

**To decide:** whether to publish and enforce a maximum request size or count.

#### 5. Extremely precise selling-unit numbers can fail when saved

The documented `0 < value ≤ 99,999` rule has no decimal-place restriction, but the current request and storage formats have finite limits. Values satisfying the number rule are therefore not all supported end to end. Request decoding currently rejects extreme representations before they can be saved; see [gap 11](#11-very-precise-input-numbers-can-be-rounded).

**To decide:** whether persistence should support every accepted precision or validation should add a storage-compatible limit.

#### 6. Duplicate variant identifiers in one update are not rejected as validation errors

An update that names the same existing variant more than once can fail as a server error and leave the whole update unchanged.

**To decide:** whether duplicate variant identifiers should be rejected during validation with a clear field error.

#### 7. Some control characters can pass validation but fail when saved

Certain text values, such as a value containing an embedded null character, can pass input validation and then cause a server error when the item or variant is stored.

**To decide:** whether these characters should be rejected during request validation.

#### 8. SKU case comparison does not cover every Unicode case equivalence

SKU comparison ignores letter case, but some Unicode spellings that users may regard as equivalent can still be treated as different, such as `straße` and `STRASSE`.

**To decide:** whether SKU comparison should use a broader Unicode case-folding rule.

#### 9. Image changes are not atomic

A failed upload can still replace an existing image or leave a new image stored without an item association. Concurrent uploads can mix the original from one request with the resized image from another. If the item is archived during an upload, the upload can report success after storing the image without updating the archived item.

**To decide:** how to keep each image pair and its item association consistent when an upload fails or overlaps another change.

#### 10. Swapping existing SKUs can be rejected as a conflict

An update that swaps two variants' existing SKUs can return a conflict even though the intended final SKUs would be unique. No part of that update is saved.

**To decide:** whether one update should support swapping or transferring a reserved SKU between variants.

#### 11. Very precise input numbers can be rounded

Numbers with more than 34 significant digits can be rounded while a request or stored selling unit is read. For example, a selling-unit quantity of `0.12345678901234567890123456789012346` can be stored as `0.1234567890123456789012345678901235`. An unrelated variant update can save a value rounded during its read. Some larger numeric representations cannot be read. This falls short of the intended exact-decimal behavior.

**Accepted for now:** business values are not expected to need this many significant digits, so no change is planned. Revisit exact input handling if that expectation changes.

{% include abbreviations.md %}
