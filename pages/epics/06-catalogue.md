---
title: Catalogue
---

# Catalogue

### Overview

The catalogue records what an organization sells. Each item has one or more variants, and each variant describes how it is sold, its optional price, and the details that distinguish it. People can add, browse, open, update, archive, and check items, and can upload an image for an item. The backend can also extract item and variant previews from images, PDFs, CSV, and Excel files without storing them; client review and submission remain separate work.

### Related / Out of scope

- **Related** — [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}). An organization must exist before its catalogue can be used.
- **Related** — [Customer Book]({{ site.baseurl }}{% link epics/05-customer-book.md %}). It uses the same organization membership and role rules for a different kind of record.
- **Not built yet** — Orders. Nothing places an order or decides whether an item without a price can be ordered.
- **Not built yet** — Search, filtering, and paging. Catalogue browsing returns all non-archived items.
- **Not built yet** — Time-based pricing and images for individual variants.
- **Client work pending** — Reviewing and submitting catalogue candidates extracted from an image, PDF, CSV file, or Excel file. The backend preview route is implemented.

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
- Image uploads are available at `POST /catalogue/file/upload/image/item`. The legacy `POST /upload/catalogue-item/image` route has been removed.
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

**Status: Backend preview implemented at `POST /catalogue/file/ai/extract`; client review and submission handling remain separate work.**

It reports inferred, missing and duplicate fields the same way as [extracting customers]({{ site.baseurl }}{% link epics/05-customer-book.md %}#8-user-extracts-customers-from-an-image-or-file). Some rules govern how the AI reads an image or file and combines a spreadsheet's general notes. These include grouping rows, continuing an item, what counts as inferred, the processing message's priority and the note wording. So far they have been checked only against a stand-in for the AI service, not the real one; until they are, treat them as intended behaviour.

**Who can reach this step: an owner or admin of the organization.**

- The user has catalogue information in a product photo, a PDF, a CSV file, or an Excel file and wants a quick starting point for the catalogue.
- An AI model reads the image or file and returns catalogue item candidates for review. Each candidate says which details were worked out, which are missing, and which are duplicated, and is marked when it needs attention.
- The user fixes anything marked before sending a candidate through the normal item-add flow.
- Nothing is stored by extraction, and an uploaded photo is not attached to an item.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User sends an image, PDF, CSV file, or Excel file with recognizable catalogue information | - The response contains candidate items with their variants and any readable details, their inferred, missing and duplicate fields, and processing and review notes |
| 2. An item has a readable name, but some details are unclear or missing | - It is still a candidate, with a short AI note on what was unclear - Optional details that the source simply does not show never mark it as needing attention |
| 3. An entry has no readable item name, but at least one other readable detail, and does not continue a named item | - It comes back as a candidate without a name - Its name is listed as a missing field and it is marked as needing attention - It counts as processed |
| 4. An entry has content, but no readable detail at all | - It is not a candidate, but counts in the total - Its row number appears in the unidentified-rows info note; for an image, this is the AI's best estimate of its position |
| 5. The source contains several rows or visible entries that belong to one item | - The AI may group them into one candidate with several variants - Uncertain grouping is described in the item's notes - Each grouped row counts as processed - A `Catalogue items created` info note gives the number of items when it differs from the processed entries - An `Item variants created` info note appears when an item has two or more variants; each item counts its variants, and an item with none counts as one |
| 6. A row has no item name, but continues the preceding item within the same extraction group | - It becomes a variant of that item - Any uncertainty is described in the item's notes - It counts as processed and is not listed as unidentified |
| 7. A meaningful source column does not map to a standard item or variant field | - Its value is preserved as a variant attribute |
| 8. A price amount is readable, but its currency is unclear or unsupported, and nothing in the source settles it | - The amount is kept without a currency - The currency is listed as a missing field and the candidate is marked as needing attention |
| 9. The currency is not written with the price, but is worked out from the document, the supplier or the processing message | - The currency is listed as an inferred field and the AI notes how it was worked out - The candidate is not marked |
| 10. A variant's selling unit cannot be read, and nothing in the source supplies it | - The variant is kept without a selling unit - The selling unit is listed as a missing field and the candidate is marked as needing attention |
| 11. A variant's selling unit is not in its own unit cell, but is worked out from the product wording, a shared header or a group, or the processing message, such as "everything is sold per kg" | - The selling unit is listed as an inferred field and the AI notes how it was worked out - The candidate is not marked - A bare unit that implies a quantity of one, a routine unit translation, or a common alias counts as read, not inferred |
| 12. An item has no variant at all | - Its variants are listed as a missing field and it is marked as needing attention |
| 13. Two candidates in the same image or file have exactly the same item name, with capital letters counting as they do when an item is added | - Both are marked as needing attention, each listing its name as a duplicate field - They are not merged - Candidates without a name are never duplicates - Never compared with items already in the catalogue |
| 14. Two variants anywhere in the same image or file have the same SKU, ignoring capital letters as adding an item does | - Each lists its SKU as a duplicate field and its candidate is marked as needing attention - Variants without a SKU are never duplicates - Never compared with variants already in the catalogue |
| 15. The image or file has no recognizable catalogue information | - No candidates, and the first info note reads `Processed 0 of 0 entries`, or `Processed 0 of <n> entries` when the source has empty or unidentified rows - The general extraction notes explain that nothing usable was detected |
| 16. A spreadsheet row is completely blank | - It is never a candidate, but counts in the total - Its row number appears in the empty-rows info note |
| 17. The file name is missing, malformed, unsupported, or does not match the content | - The request is rejected before the file is sent to the AI - Content that is recognisably another type, such as a photo named `.pdf`, is rejected - Content that matches no recognisable type, named `.pdf`, is sent to the AI as a PDF and fails as in scenario 31 |
| 18. The file cannot be read as a CSV or Excel file, or is larger than 20 MB | - The request fails and no candidate result is returned |
| 19. The AI service has a temporary failure | - The service tries again up to two more times - If every attempt fails, the request fails with no partial result |
| 20. The AI service rejects the request or sends back an unusable answer | - The request fails without retrying that response |
| 21. A member without an owner or admin role tries this step | - The request is rejected |
| 22. The user reads the same image or file again | - The read is allowed and nothing from the previous read is reused or stored |
| 23. The user sends an Excel workbook with several sheets | - Only the first sheet is read |
| 24. The user sends a legacy `.xls` file | - It is read in the same way as an `.xlsx` file |
| 25. The user sends a spreadsheet with many rows | - Every row is read in groups (requirement 15) - Row numbers and duplicate checks still refer to the whole file - General notes that say the same thing are combined (requirement 12) - If any group fails, no partial result is returned |
| 26. The user sends a supported file whose extension uses different letter case | - It is accepted when the extension matches the actual content |
| 27. The user supplies a processing message | - It takes priority over the default reading rules, within the limits of requirement 17, across the whole upload and every spreadsheet group - A detail it asks to leave out is meant to count as absent, not missing; today a dropped selling unit or currency is still reported missing ([gap 14](#14-a-detail-dropped-on-request-is-still-reported-missing)) |
| 28. The message is empty, whitespace-only, ambiguous, or conflicts with the source | - An empty message is treated as no message - Otherwise it is applied as far as the source supports, and anything it could not settle is described in the notes - The request is not rejected only for being ambiguous |
| 29. The processing message is longer than 2,000 characters after trimming | - The request is rejected as a bad request |
| 30. User sends a PDF, typed or scanned, including one laid out as a table | - It is read like an image, as one whole document, so the AI can group an item that continues onto the next page - Positions are counted top to bottom through all pages, starting at 1: the 3rd entry on page 2, after 20 entries on page 1, is at 23 - It never has empty rows, and its general notes are never combined - The processing message applies as for an image |
| 31. User sends a PDF that is password-protected, damaged, or too long for the AI service (roughly 100 pages), or whose read runs out of time | - The page count is not checked and the PDF is not opened before it is sent - The AI service cannot read it, and the request fails with a server error and no partial result, the same as any other AI failure |

#### Requirements

1. PNG, JPEG, and WEBP images, PDFs, and genuinely readable CSV, `.xls`, and `.xlsx` files, are accepted up to 20 MB. Spreadsheet row count is not capped. Typed and scanned PDFs are both read, always in the same way as an image. A PDF's pages are not counted or checked: one the AI service cannot read fails the request with a server error.
2. The file name is required, 1–255 trimmed characters, and must have a supported extension matching the actual content, ignoring letter case. When the content matches no recognisable type, a `.pdf` name is trusted and the AI service decides (scenario 31).
3. The response contains candidate catalogue items, system-written processing summaries in `infoNotes`, and the AI's general comments about the source in top-level `extractionNotes`. Every note the AI writes, on an item or in top-level `extractionNotes`, has at most ten words and one observation, in plain everyday words a customer would understand, never technical words such as "infer", "context", "schema" or "format codes". Separate observations appear as separate bullets and a note never contains a semicolon; a longer observation is split into separate notes or shortened, and a long list of names is summarised rather than listed, such as `Eight marketing columns were not used`.
4. A candidate carries the details of an item added in [step 1](#1-user-adds-an-item): an optional name, an optional description and its variants. It also carries its inferred, missing and duplicate fields, whether it needs attention, and its own extraction notes. A name may be absent but is never empty when returned. A candidate is returned when its name, or at least one other detail, can be read. An entry with no readable detail is an unidentified row.
5. A variant has an optional SKU, label, description, price, selling unit and attribute map. A variant is kept even when its selling unit cannot be read, and a price keeps its readable amount even when its currency cannot be settled. Nothing a candidate holds is checked against the add-item rules until it is added; the price amount and selling unit rules apply then. Meaningful unmapped source columns become attributes; repeated attribute names keep the last value.
6. Interpret unit abbreviations, translations, product wording, and nearby context using the closest supported meaning. Accept any language or script, including mixed-language wording, regional shorthand, symbols, and transliterations; map their meaning to the closest supported English value without requiring exact keys or a predefined alias. Parse combined number/unit expressions; a bare unit implies one. Understand the source layout and match labels and values to catalogue fields by meaning, across formats and languages. Keep records and shared sections separate. Check each field independently, then recheck missing-field issues against the source. A blank, unclear, or intentionally omitted field must not invalidate another readable field. Read unit and price columns independently. A blank unit requires review unless the product wording, an explicit shared header or group, or the processing message supplies it; do not copy neighbouring units or assume a typical unit for that product. A request to transform known units does not supply a missing source unit. Keep a readable unit when its price is blank. A blank optional price is omitted without a warning; routine unit translation and an implied quantity of one also need no warning. Translate non-English meaning internally, compare supported English values, and check confidence in the match. If no supported meaning fits confidently, the value is left empty: an unresolved selling unit or currency is then a missing field. Routine aliases need no warning. Readable prices may use a supported currency worked out from document-wide or supplier context, with a shared assumption noted once. Preserve explicit currency and interpret localized digits and number separators from context. Do not invent price amounts or arbitrary identifiers; a price whose amount cannot be read is left out with a note.
7. Every candidate lists its inferred fields (worked out rather than read), its missing fields (absent, and needed to add the item) and its duplicate fields (matching another candidate in the same upload). Each entry is a path to that value in the candidate, listed under **FieldInfo** below. A name is listed as missing when absent and as a duplicate per requirement 9. The variants are listed as missing when there are none. A SKU is listed as a duplicate per requirement 9. A selling unit or a price currency is listed as missing when absent and as inferred per requirement 10. The client uses each path to mark the matching input red (missing or duplicate) or yellow (inferred).
8. A candidate needs attention exactly when it has a missing or duplicate field. That means its name is missing or duplicated, it has no variant, a variant has no selling unit, a price has no currency, or a SKU is duplicated. Inferred fields and notes never mark a candidate.
9. Duplicates are worked out by the system over the whole upload, for every source, using the same comparison as the catalogue's own uniqueness rules (functional rule 3). Item names must match exactly, with capital letters counting, and only candidates with a name are compared. SKUs match ignoring capital letters, across every variant of every candidate, and only present SKUs are compared. Duplicates are never compared with stored items or variants, and they are never merged.
10. Only a selling unit and a price currency can be inferred. The selling unit is inferred when worked out from product wording, a shared header or a group, or the processing message, rather than the variant's own unit value. The currency is inferred when worked out from the document, the supplier or the processing message, rather than written with the amount. The AI adds a short note each time. A bare unit that implies a quantity of one, a routine unit translation and a common alias count as read.
11. An entry is one original row of a file, or one visible entry of an image. It counts once as processed when it gives, or joins, at least one candidate, with or without a name. The info notes are, in order:
    - `Processed <processed> of <total> entries`.
    - `Catalogue items created <count>`, only when the number of candidates differs from the processed entries.
    - `Item variants created <count>`, only when the count differs from the number of candidates. Each candidate adds its number of variants, or 1 when it has none, so items with 1, 0 and 2 variants give 4. The line therefore appears only when some item has two or more variants.
    - `Empty rows (<count>) at [<rows>]` and `Unidentified rows (<count>) at [<rows>]`, only when they have rows.

    Row numbers are the ones the person sees in their own file, where the label row is row 1. The total is processed plus empty plus unidentified entries. An image has no empty rows, and its row positions are the AI's top-to-bottom estimate, starting at 1. A PDF is treated as an image: it has no empty rows, and its positions are the AI's estimate counted straight through all its pages. The AI reports the counts and rows; checking them for consistency is deferred.
12. A candidate's extraction notes are the AI's own short observations, about the item and then about each variant in order. They are unprefixed and never de-duplicated. The system writes no candidate notes, and the AI does not repeat which fields are missing, inferred or duplicated. The general extraction notes are the AI's comments about the source as a whole. For a spreadsheet read in two or more groups, notes that say the same thing are combined into one, and every other note is kept exactly as written, in file order. If combining fails, each group's notes come back unchanged, so a note may repeat, and the read still succeeds. An image's or a PDF's general notes are never combined, because the whole source is read at once.
13. Nothing is stored: no item, variant, image, file, or extraction response. The normal item-add request remains the only way to create catalogue records, and its existing validation remains unchanged.
14. Each AI attempt may take up to three minutes by default. Temporary failures are tried again up to two more times; rejected requests and unusable answers are not retried.
15. Large spreadsheets are read in groups of up to 100 rows, with up to 4 groups at a time. Any failed group fails the whole read and returns no partial candidates.
16. An optional processing message is trimmed plain text up to 2,000 characters. An empty or whitespace-only message is treated as absent.
17. The processing message applies to the whole upload and every spreadsheet group, and takes priority over the default reading rules. Three things stay fixed:
    - Every result still fits the response's rules; a request that cannot fit is applied as far as possible and explained in a note.
    - It never adds details the source does not support.
    - The system still works out the counts, the duplicate and missing fields and needs-attention, so the message cannot hide missing or unclear data.

    An entry it asks to skip is counted nowhere. A detail it asks to leave out is meant to be absent rather than missing, but today a dropped selling unit or currency is still reported missing ([gap 14](#14-a-detail-dropped-on-request-is-still-reported-missing)). It is never stored or returned.
18. A row without an item name that appears to continue a named item within the same extraction group may attach to it, with uncertainty described in the item's notes. Candidates from different groups remain separate, and their duplicates are still reported. Cross-batch grouping is deferred under [gap 12](#12-one-item-can-be-split-between-extraction-batches).

#### Request / Response / Outcome

One upload carries whichever source the user has — an image, a PDF, a CSV file, or an Excel file — in the same request shape.

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form | ✅ | The organization whose catalogue is being prepared |
| File | `Binary` | PNG, JPEG or WEBP; PDF; or genuinely readable as CSV or Excel (`.xls` or `.xlsx`); up to 20 MB | ✅ | The image or file to read |
| File Name | `String` | 1–255 characters, trimmed; supported extension (`.png`, `.jpg`, `.jpeg`, `.webp`, `.pdf`, `.csv`, `.xls` or `.xlsx`) matching the actual content, ignoring letter case | ✅ | The source file name; it is not stored |
| Processing Message | `String` | Single line; up to 2,000 characters, trimmed plain text | ❌ | Optional instructions for reading the uploaded source, which take priority over the default reading rules (requirement 17) |

**Response — `CatalogueFileAiExtractPostResponse`**

In this response, a list or map with nothing in it is left out rather than sent empty, so a client treats a missing list as empty. That is why those fields are marked not required below.

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| `candidates` | `CatalogueItemCandidate[]` | Left out when empty | ❌ | Item candidates for review, in source order |
| `infoNotes` | `String[]` | Always starts with the processed-of-total summary; an item count only when it differs from the processed entries; a variant count (each item adds its variants, or 1 when it has none) only when it differs from the item count; empty-row and unidentified-row summaries only when they have rows | ✅ | Processing summaries for this source |
| `extractionNotes` | `String[]` | Left out when empty; plain strings, one point each, no bullet prefix | ❌ | The AI's general comments about the source; for a spreadsheet read in two or more groups, notes that say the same thing are combined (requirement 12) |

**CatalogueItemCandidate**

The details follow the same field rules as **Request — adding one item** ([step 1](#1-user-adds-an-item)), except where a value below may be absent.

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| `name` | `String` | 1–255 characters, trimmed | ❌ | The item name; absent when not readable, which marks the candidate |
| `description` | `String` | 1–255 characters, trimmed | ❌ | The item description |
| `variants` | `CatalogueItemVariantCandidate[]` | Left out when empty | ❌ | The variants as read; none marks the candidate. See **CatalogueItemVariantCandidate** below |
| `inferredFields` | `FieldInfo[]` | Left out when empty | ❌ | Details worked out rather than read. See **FieldInfo** below |
| `missingFields` | `FieldInfo[]` | Left out when empty | ❌ | Details that are absent and must be supplied before adding |
| `duplicateFields` | `FieldInfo[]` | Left out when empty | ❌ | Details that match another candidate in the same upload |
| `needsAttention` | `Boolean` | True exactly when `missingFields` or `duplicateFields` is non-empty | ✅ | Whether the user must fix something before adding |
| `extractionNotes` | `String[]` | Left out when empty; plain strings, one point each, no bullet prefix | ❌ | The AI's own observations about the item, then about each of its variants |

**FieldInfo**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| `path` | `String` | A JSON Pointer ([RFC 6901](https://www.rfc-editor.org/rfc/rfc6901)) from the candidate object, with 0-based list positions; one of `/name`, `/variants`, `/variants/{i}/sku`, `/variants/{i}/sellingUnit`, `/variants/{i}/price/currency` | ✅ | The exact value in the candidate this is about, such as `/variants/1/sellingUnit` for the second variant's selling unit |

**CatalogueItemVariantCandidate**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| `sku` | `String` | 1–255 characters, trimmed | ❌ | The stock code |
| `label` | `String` | 1–255 characters, trimmed | ❌ | The variant label |
| `description` | `String` | 1–255 characters, trimmed | ❌ | The variant description |
| `attributes` | `Map<String, String>` | Left out when empty; names and values are 1–255 characters and trimmed | ❌ | Structured or unmapped details that distinguish the variant |
| `price` | `CatalogueItemVariantPriceCandidate` | Present when the amount is readable | ❌ | The price. See **CatalogueItemVariantPriceCandidate** below |
| `sellingUnit` | `CatalogueItemVariantSaleUnit` | See **CatalogueItemVariantSaleUnit** | ❌ | How the variant is sold; absent when not readable, which marks the candidate |

**CatalogueItemVariantPriceCandidate**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| `amount` | `BigDecimal` | — ; the add-item price rules are checked only when the item is added | ✅ | The amount as read |
| `currency` | `Currency` | `EUR`, `USD`, or `GBP` in exact uppercase form | ❌ | The currency, as read or worked out from context; absent when it cannot be settled, which marks the candidate |

Examples:
- A file whose AI counts are 26 entries (19 processed, 6 blank, 1 unreadable), where the 19 processed rows become 17 items with 19 variants because the 3 garden hose rows are grouped into one item, gives separate info notes. They are `Processed 19 of 26 entries`, `Catalogue items created 17`, `Item variants created 19`, `Empty rows (6) at [2, 3, 15, 16, 30, 31]` and `Unidentified rows (1) at [28]`. The items note appears because 17 items differ from the 19 processed entries, and the variants note because the count of 19 differs from the 17 items. Every item here has at least one variant.
- One row read as one item with two sizes gives `Processed 1 of 1 entries` and `Item variants created 2`. The items note is left out because 1 item equals the 1 processed entry, and the variants note appears because the item has 2 variants.
- Ten rows read as ten items, three of them with two sizes and the rest with one variant, give `Processed 10 of 10 entries` and `Item variants created 13`, with no items note.
- Ten rows read as ten items with one variant each give only `Processed 10 of 10 entries`.
- Three items with 1, 0 and 2 variants give `Item variants created 4`. An item without variants counts as one, so ten items, each with one variant or none, give no variants note.
- `The source is cropped` belongs in the general extraction notes, and `SKU is visible but could not be read` in a candidate's notes.
- A candidate whose second variant has no readable selling unit lists `/variants/1/sellingUnit` as a missing field.
- A price list with a € sign only in its header lists `/variants/0/price/currency` as an inferred field, with a note such as `Euro assumed from the price list header`.
- `Olive Oil` and `olive oil` are different names, so neither is a duplicate. SKUs `AB-1` and `ab-1` are duplicates, and each lists its `/variants/{i}/sku`.
- Only missing and duplicate fields mark a candidate as needing attention; inferred fields and notes never do.

**Outcome**

- The user receives a preview of catalogue item candidates, with processing and review notes.
- Each candidate shows which of its details were worked out, which are missing (a name, its variants, a selling unit or a currency) and which are duplicated (a name or a SKU), so the user can fix them first. Its notes explain anything else the AI observed.
- An entry with readable details but no readable name still comes back, without a name, for the user to complete.
- Nothing is stored, and an uploaded image is not attached to an item.
- The user adds resolved candidates through [step 1](#1-user-adds-an-item), where the existing validation and duplicate checks still apply.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | The organization is missing, the file name is missing or malformed, the file name and content do not agree, or the processing message exceeds 2,000 characters after trimming |
| 401 | `UNAUTHORIZED_ERROR` | The access token is missing, invalid, or expired |
| 403 | `FORBIDDEN_ERROR` | Personal onboarding is incomplete or the person's role does not allow extraction |
| 500 | `INTERNAL_SERVER_ERROR` | The upload cannot be read, is larger than 20 MB, is not genuinely readable as a CSV or Excel file, the person does not belong to the organization, the AI service fails (including a PDF it cannot read because it is password-protected, damaged or too long, or because its content is not a PDF), or an unexpected error occurs |

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

#### 12. One item can be split between extraction batches

When an item's variants fall into different 100-row batches, the preview can contain separate candidates for that item. Matching names are flagged as duplicates. A row without an item name at the start of a batch, continuing an item from the previous batch, comes back as a separate candidate without a name, with its name listed as missing.

**Deferred:** combining these candidates and linking continuations across batches will be tackled later. Users review the separate candidates for now.

#### 13. Availability checks count the record being edited

Checking an item's unchanged name or a variant's unchanged SKU reports it as taken, because the check also counts that same record. This can make a valid edit look unavailable.

**Deferred to the next PR:** allow the check to ignore the item or variant being edited. Checks for new items and variants continue to consider every existing non-archived record.

#### 14. A detail dropped on request is still reported missing

Say a processing message for [extracting catalogue items](#10-user-extracts-catalogue-items-from-an-image-or-file) asks for selling units or currencies to be left out. Each one dropped is still listed as missing, and the candidate is marked as needing attention. An item name the message asks to leave out is treated the same way. Scenario 27 and requirement 17 intend such a detail to count as absent, so it would not mark the candidate. This is the same behaviour as [Customer Book gap 7]({{ site.baseurl }}{% link epics/05-customer-book.md %}#7-a-detail-dropped-on-request-is-still-reported-missing).

Every such candidate then looks as though it needs fixing, and it cannot be added until the user supplies the very detail they asked to drop.

**To decide:** whether a detail dropped on request should stop counting as missing, and if so how the system should tell it apart from one that could not be read. Answer it together with Customer Book gap 7.

{% include abbreviations.md %}
