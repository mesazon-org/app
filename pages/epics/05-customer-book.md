---
title: Customer Book
---

# Customer Book

### Overview

Every organization keeps a book of the people and companies it trades with. It is the organization's own address book, and it is where future orders will point.

Customers can be added, browsed, opened, updated, and archived. Users can also extract customer candidates from images or files.

### Related / Out of scope

- **Related** — [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}). An organization must exist first, and every request here names which organization it is for.
- **Out of scope** — Who may belong to an organization and what each role means. That is set up with the organization itself.
- **Out of scope** — Checking an image's or file's candidates against customers already stored in the book. The only duplicate check made when reading an image, CSV file, or Excel file is between candidates found within that same image or file.
- **Not built yet** — Orders. The customer book exists so that orders can point at a customer later, but nothing places or records an order yet.
- **Not built yet** — Search and paging. The list can be narrowed to an explicit set of customer identifiers, but it still returns all matching active customers in one go, sorted by name.

### Requirements across the epic

Every request in this epic names the organization it applies to, and only touches that organization's book. Nothing here can see another organization's customers.

#### Functional

1. A customer is either a **person** or a **business**, chosen when they are added and fixed for good. There is no converting one into the other.
2. Only businesses have **contacts** — the individual people you deal with inside that company. A contact is never itself a customer and can never be the target of an order.
3. A customer's name must be unique among that organization's active customers **of the same kind**. A person and a business may share a name, and archiving a customer frees their name for reuse.
4. Customers and business contacts are **archived**, never deleted. Archiving either one is permanent.
5. A customer may have any number of email addresses and phone numbers. Each business contact has at most one email address and one phone number, and no address.
6. A customer has no address, exactly one address marked as both shipping and billing, or exactly two addresses, one shipping and one billing in either order. Whenever addresses are sent, anything else is rejected: three or more, two of the same type, a single shipping-only or billing-only address, or a both-purposes address alongside another.

#### Non-functional

1. Every request needs a signed-in person who has finished personal onboarding, and who belongs to the organization named in the request.
2. Reading is open to any member. Adding, changing, archiving and managing contacts are limited to owners and admins.
3. When a request contains several problems at once, all of them are reported together rather than one at a time, and each is tied to the exact entry that caused it — including the position of a contact inside a business.
4. Adding several customers at once is all-or-nothing. If any one of them fails, none of them are stored.
5. A missing, invalid, or expired access token is refused with the same error code used everywhere else in the product for a rejected access token.

### User flow

Unlike the earlier epics, these steps are not a single journey. They are the stages of a customer's life in the book, and each stands on its own.

1. [User Adds a Customer](#1-user-adds-a-customer)
2. [User Browses the Customer Book](#2-user-browses-the-customer-book)
3. [User Opens a Customer](#3-user-opens-a-customer)
4. [User Updates a Customer](#4-user-updates-a-customer)
5. [User Manages a Business's Contacts](#5-user-manages-a-businesss-contacts)
6. [User Archives a Customer](#6-user-archives-a-customer)
7. [User Checks Whether a Customer Name Is Free](#7-user-checks-whether-a-customer-name-is-free)
8. [User Extracts Customers from an Image or File](#8-user-extracts-customers-from-an-image-or-file)

### Prerequisites

**Which organization** — every request names the organization whose book it is touching, and the person must belong to it.

**Roles** — what a member may do here:

| **Role** | **May read** | **May add, change, archive** |
| --- | --- | --- |
| Owner | Yes | Yes |
| Admin | Yes | Yes |
| User | Yes | No |

**Customer status** — a customer is `Active` or `Archived`. New customers start active. Archiving is one-way.

**Business contact status** — a contact is `Active` or `Archived`. New contacts start active. Archiving is one-way; archived contacts keep their details.

**Default contact details** — email and phone lists may be left empty. When a list has entries, exactly one of them must be marked as the default. Addresses have no default.

**Address type** — every address is marked as shipping, billing, or both. Both (`ShippingAndBilling`) means one address serves both purposes. Functional rule 6 says which combinations are allowed.

### 1. User Adds a Customer

**Who can reach this step: an owner or admin of the organization.**

- User chooses whether they are adding a person or a business.
- User fills in the name and whatever contact details and addresses they have.
- For a business they may also record the people they deal with there.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User adds a person | - Stored as a person, active, with no tax id - Name must not match another active person in this organization |
| 2. User adds a business | - Stored as a business, active - May carry a tax id, which a person may not - Any contacts given are saved with it, all or nothing |
| 3. User adds several customers at once | - All are stored together or none are |
| 4. User adds a customer whose name is already taken by an active customer of the same kind | - Rejected as a conflict, naming what clashed - A person and a business may share a name; only same-kind clashes are refused |
| 5. User gives two contacts at the same business the same email or phone number | - Accepted - Contacts may share email and phone values, leave them blank, or have neither |
| 6. User submits several bad entries at once | - Every problem is reported together - Each is tied to the entry that caused it, including which contact inside which business |
| 7. User leaves the contact lists empty | - Accepted. The customer simply has no recorded contact details |
| 8. A member with the ordinary user role tries to add a customer | - Rejected. Reading is open to everyone; changing is not |
| 9. User adds a customer with one address for both shipping and billing, or a shipping address and a billing address, where each address has at least one non-empty text field | - Every address is stored with its type - None is marked as a default - An empty list is accepted; the customer simply has no recorded address |
| 10. User gives an address with some text fields missing, but at least one field has content | - Accepted - The address is stored with the content that was supplied |
| 11. User gives an address whose text fields are all empty, with a type other than shipping, billing, or both, or any other combination of addresses | - Rejected and nothing is stored |

#### Requirements

1. A name is required. Everything else — contact details, addresses, tax id, contacts — is optional.
2. A tax id may be recorded for a business and never for a person.
3. When email or phone lists are given, every entry must be valid and exactly one must be marked as the default.
4. A name must be unique among active customers of the same kind within the organization.
5. Contacts at the same business may share email addresses and phone numbers, whether they are active or archived.
6. Adding several customers at once succeeds completely or not at all.
7. Every address text field is optional. Missing or null values are absent. Supplied values must contain 1–255 characters with no leading or trailing whitespace; empty or whitespace-only values are rejected for that field. An address must contain at least one non-empty text field, its type is required, and together the addresses must be an allowed combination (functional rule 6).

#### Request / Response / Outcome

A customer can be added one at a time or as a mixed batch of people and businesses. The three forms carry the same two shapes — the mixed batch wraps them in lists:

| **What the user is adding** | **What is sent** |
| --- | --- |
| One person | One `CustomerBookIndividualPostRequest` |
| One business | One `CustomerBookBusinessPostRequest` |
| A mixed batch | `CustomerBookIndividualPostRequest[]` and `CustomerBookBusinessPostRequest[]` together |

The person and business forms are distinct. A person has a name and no tax id or contacts; a business has a business name and may have both.

**Request — CustomerBookIndividualPostRequest**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed | ✅ | The person's name |
| Emails | `CustomerEmailEntry[]` | Empty by default | ❌ | Contact email addresses. See **CustomerEmailEntry** below |
| Phone Numbers | `CustomerPhoneNumberEntry[]` | Empty by default | ❌ | Contact numbers. See **CustomerPhoneNumberEntry** below |
| Addresses | `CustomerAddressEntry[]` | Empty by default; otherwise one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Where they are. See **CustomerAddressEntry** below |

**Request — CustomerBookBusinessPostRequest**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Business Name | `String` | 1–255 characters, trimmed | ✅ | The company's name |
| Emails | `CustomerEmailEntry[]` | Empty by default | ❌ | Contact email addresses. See **CustomerEmailEntry** below |
| Phone Numbers | `CustomerPhoneNumberEntry[]` | Empty by default | ❌ | Contact numbers. See **CustomerPhoneNumberEntry** below |
| Tax ID | `String` | 1–255 characters, trimmed | ❌ | The company's tax reference. A person may never have one |
| Customer Business Contacts | `CustomerBusinessContactInsert[]` | Empty by default | ❌ | People inside the business. See **CustomerBusinessContactInsert** below |
| Addresses | `CustomerAddressEntry[]` | Empty by default; otherwise one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Where they are. See **CustomerAddressEntry** below |

The shapes used above and throughout this epic:

**CustomerEmailEntry**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854); max 255 characters | ✅ | One contact email address |
| Is Default | `Boolean` | Exactly one entry in the list must be true | ✅ | Marks the email address to use by default |

**CustomerPhoneNumberEntry**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Phone Number | `PhoneNumberRequest` | — | ✅ | One contact number. See **PhoneNumberRequest** below |
| Is Default | `Boolean` | Exactly one entry in the list must be true | ✅ | Marks the number to use by default |

**PhoneNumberRequest**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Phone National Number | `String` | 1–255 characters, trimmed; must be a real number for its country | ✅ | The number without its country code |
| Phone Country Code | `String` | 1–255 characters, trimmed; must be a real country dialling code | ✅ | The country dialling code |

**CustomerAddressEntry**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Address | `AddressRequest` | — | ✅ | Where it is. See **AddressRequest** below |
| Address Type | `AddressType` | `Shipping`, `Billing` or `ShippingAndBilling` | ✅ | What the address is used for. `ShippingAndBilling` means one address serves both |

**AddressRequest**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Address Line 1 | `String` | 1–255 characters, no leading or trailing whitespace when present | ❌ | Street address |
| Address Line 2 | `String` | 1–255 characters, no leading or trailing whitespace when present | ❌ | Street address, continued |
| City | `String` | 1–255 characters, no leading or trailing whitespace when present | ❌ |  |
| Postal Code | `String` | 1–255 characters, no leading or trailing whitespace when present | ❌ |  |
| Country | `String` | 1–255 characters, no leading or trailing whitespace when present | ❌ | Free text, as the person types it |

**CustomerBusinessContactInsert**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed | ✅ | The contact's name |
| Role | `String` | 1–255 characters, trimmed | ❌ | What they do there |
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854); max 255 characters | ❌ |  |
| Phone Number | `PhoneNumberRequest` | — | ❌ |  |

**Response**

Adding one person or one business returns only the new customer's identifier. A mixed batch returns only the new customer identifiers, with individuals first and businesses second, preserving the order within each input list. To get a business's contact identifiers, open its business details.

**Response — one individual**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | The new customer's identifier |

**Response — one business**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | The new customer's identifier |

**Response — a mixed batch**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer IDs | `UUID[]` | Individuals first, then businesses; order within each list is preserved | ✅ | Identifiers of the new customers; may be empty |

**Outcome**

- The customer is stored as active, fixed as either a person or a business.
- For a business, any contacts given are stored along with it.
- For a batch, every customer in it is stored together, or none is.
- On any refusal nothing at all is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid, reported together with the entry each belongs to |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - A customer of this kind already has that name |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 2. User Browses the Customer Book

**Who can reach this step: any member of the organization.**

- User opens the customer book and sees everyone currently in it.
- User may provide a set of customer identifiers to see only those customers.
- The list is sorted by name, ignoring capitalisation.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens the customer book | - Every active customer in the organization is returned - Each entry says whether it is a person or a business - Sorted by name, ignoring capitalisation |
| 2. The organization has archived customers | - Archived customers do not appear - There is no way to list them again - See [gap 3](#3-archiving-is-final-and-archived-customers-cannot-be-found-again) |
| 3. The organization has no customers yet | - An empty list is returned |
| 4. User supplies customer identifiers | - Matching active customers are returned once each - Missing, archived, or other-organization identifiers are omitted without an error |
| 5. User supplies no identifiers or an empty list | - Every active customer is returned |

#### Requirements

1. Any member may read the customer book, whatever their role.
2. The list contains only active customers.
3. Each entry carries enough to show a row and open it: the identifier, the name, and whether it is a person or a business.
4. The list is sorted by name, ignoring capitalisation.
5. An optional identifier list narrows the result. An omitted or empty list means all active customers.

#### Request / Response / Outcome

**Request**

The request names the organization and may include an identifier list. There is no text search or paging.

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer IDs | `UUID[]` | Empty by default | ❌ | Return only these active customers; missing, archived, or foreign identifiers are omitted |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customers | `CustomerSummary[]` | Sorted by name, ignoring capitalisation | ✅ | The book. See **CustomerSummary** below |

**CustomerSummary**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Identifies the customer, used to open them |
| Name | `String` | — | ✅ | The person's or business's name |
| Customer Type | `CustomerType` | `Individual` or `Business` | ✅ | Which kind, so the right screen can be opened |

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 3. User Opens a Customer

**Who can reach this step: any member of the organization.**

- User picks a customer from the book.
- The summary says whether the customer is a person or a business, and the app uses that kind to choose the matching details request.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens an existing individual or business | - Their full details are returned, including customer contact details and every address with its type - A business also includes its active business contacts - The app chooses the individual or business details request using the kind shown in the summary |
| 2. User opens a customer that has been archived | - Their details are unavailable, just like a customer that is missing |
| 3. User opens a customer that does not exist | - Reported as a server error rather than "not found" — see [gap 1](#1-looking-up-an-unavailable-customer-is-reported-as-a-server-error) |

#### Requirements

1. The app opens an individual or business through its matching details request, using the kind shown in the customer summary.
2. Archived customers cannot be opened. Their customer and contact details remain stored.
3. A business read includes active contacts and omits archived contacts. An individual read has no business contacts.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Which customer to open |

The app uses the customer's kind from the summary to choose the matching individual or business details request. Each response contains only the fields for that kind. A business includes active contacts; archived contacts are omitted.

**Response — Individual**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Identifies the customer |
| Full Name | `String` | — | ✅ | The person's name |
| Emails | `CustomerEmailEntry[]` | May be empty | ✅ | Every recorded email address, each marked default or not |
| Phone Numbers | `CustomerPhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Addresses | `CustomerAddressEntry[]` | May be empty | ✅ | Every recorded address, each with its type |

**Response — Business**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Identifies the customer |
| Business Name | `String` | — | ✅ | The company's name |
| Emails | `CustomerEmailEntry[]` | May be empty | ✅ | Every recorded email address, each marked default or not |
| Tax ID | `String` | — | ❌ | Present when recorded |
| Phone Numbers | `CustomerPhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Addresses | `CustomerAddressEntry[]` | May be empty | ✅ | Every recorded address, each with its type |
| Customer Business Contacts | `CustomerBusinessContact[]` | May be empty; active contacts only | ✅ | Active business contacts; archived contacts are omitted |

**CustomerBusinessContact**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contact ID | `UUID` | Canonical 36-character form | ✅ | The contact's identifier, used for later updates or archiving |
| Full Name | `String` | — | ✅ | The contact's name |
| Role | `String` | — | ❌ | Present only if recorded |
| Email | `String` | — | ❌ | Present only if recorded |
| Phone Number | `PhoneNumberRequest` | — | ❌ | Present only if recorded |

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - No available customer of that kind with that identifier - The person does not belong to the organization - Unexpected error |

### 4. User Updates a Customer

**Who can reach this step: an owner or admin of the organization.**

- User opens a customer and changes their details.
- For a business, the same change may insert, update, or archive its contacts.
- The kind of customer can never be changed — only their details.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User changes an active customer's details | - The change is saved - Omitted email, phone, and address lists stay unchanged - An explicit empty list (`[]`) clears that detail - A non-empty list validates and replaces the whole detail - Single fields left out are left as they were - A null tax ID clears it - Supplied names and tax IDs replace them; names cannot be cleared |
| 2. User renames a customer to a name another active customer of the same kind already has | - Rejected as a conflict |
| 3. User changes a customer that has been archived | - Nothing happens, and the change is reported as successful - See [gap 2](#2-changes-to-an-archived-customer-are-silently-discarded) |
| 4. User changes a customer that does not exist | - Nothing happens, and it is reported as successful |
| 5. A member with the ordinary user role tries to make a change | - Rejected |
| 6. User replaces an address with some text fields missing, but at least one field has content | - Accepted - The partial address is stored |
| 7. User replaces an address whose text fields are all empty | - Rejected and nothing is stored |
| 8. User changes a business and its contacts together | - Customer fields and all contact changes are applied atomically |
| 9. A contact to update is missing, archived, or belongs to another business | - The whole change is rejected and nothing is stored |
| 10. A contact to archive is missing, archived, or belongs to another business | - Nothing changes for that contact and the request still succeeds |
| 11. User changes a missing or archived business | - Nothing happens, and the change is reported as successful |
| 12. A contact ID is repeated in either list, or appears in both update and archive lists | - Rejected with a validation error before any change is stored |

#### Requirements

1. Only active customers can be changed. The kind is fixed at creation and never changes.
2. Email, phone, and address lists are nonnullable and replaced entirely when supplied, rather than merged. Leaving a list out keeps it, an explicit empty list (`[]`) clears it, and a non-empty list validates and replaces it.
3. Names are optional replacement fields: leaving one out keeps the current name, and a name cannot be cleared. A business tax ID is nullable: leaving it out keeps the current value, null clears it, and a supplied value replaces it.
4. A rename must still leave the name unique among active customers of the same kind.
5. Address text fields are optional, but every address must contain at least one non-empty text field; an address with all five fields empty is rejected.
6. Business contact changes are part of the business update and are atomic with its other fields. Contacts not named in a list remain unchanged.
7. New contacts are active. An update changes only the fields supplied; an archive is permanent and keeps the contact's details.
8. A missing, archived, or foreign contact referenced for update rejects the whole business update. A missing, archived, or foreign contact named for archive is ignored, matching the catalogue's update and archive behavior.
9. Contact IDs must be unique within each list and across the update and archive lists. Duplicate or overlapping IDs are rejected before any write.

#### Request / Response / Outcome

There are two ways to update, one per kind, and the customer identifier is part of the request path. Updating a person through the business form finds nothing, and changes nothing.

**Request — updating a person**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed; cannot be cleared | ❌ | Replacement name; leave out to keep the current name |
| Emails | `CustomerEmailEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list | ❌ | Send the complete set, not just additions |
| Phone Numbers | `CustomerPhoneNumberEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list | ❌ | Send the complete set, not just additions |
| Addresses | `CustomerAddressEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list; when non-empty, one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Send the complete set, not just additions |

**Request — updating a business**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Business Name | `String` | 1–255 characters, trimmed; cannot be cleared | ❌ | Replacement name; leave out to keep the current name |
| Emails | `CustomerEmailEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list | ❌ | Send the complete set, not just additions |
| Tax ID | `String` | 1–255 characters, trimmed; explicit null clears it | ❌ | Replacement tax ID; leave out to keep the current value |
| Phone Numbers | `CustomerPhoneNumberEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list | ❌ | Send the complete set, not just additions |
| Addresses | `CustomerAddressEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list; when non-empty, one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Send the complete set, not just additions |
| Customer Business Contacts Insert | `CustomerBusinessContactInsert[]` | Empty by default | ❌ | Contacts to add as active contacts |
| Customer Business Contacts Update | `CustomerBusinessContactUpdate[]` | Empty by default | ❌ | Existing contacts to change |
| Customer Business Contacts Archive | `CustomerBusinessContactArchive[]` | Empty by default | ❌ | Existing contacts to archive permanently |

The customer identifier is supplied in the path for both forms. Individual updates have no contact lists. Business updates apply all three contact lists together with the business fields.

**CustomerBusinessContactUpdate**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contact ID | `UUID` | Canonical 36-character form | ✅ | Which existing contact to change |
| Full Name | `String` | 1–255 characters, trimmed | ❌ | Replacement name; omitted means unchanged |
| Role | `String` | 1–255 characters, trimmed | ❌ | Replacement role; null clears it |
| Email | `String` | Standardised email; max 255 characters | ❌ | Replacement email; null clears it |
| Phone Number | `PhoneNumberRequest` | A real number for its country | ❌ | Replacement phone; null clears it |

**CustomerBusinessContactArchive**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contact ID | `UUID` | Canonical 36-character form | ✅ | Identifies one contact to archive |

**Response**

Response is empty. A successful change answers with nothing but a success status — and so does a change that quietly did nothing, which is what makes [gap 2](#2-changes-to-an-archived-customer-are-silently-discarded) hard to notice.

**Outcome**

- For an active customer the details are replaced as described and nothing else changes.
- For an active business, contact inserts, updates, and archives are applied with the customer fields in one atomic change.
- For an archived or missing customer nothing is stored, and the answer is the same as success.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid - A contact ID is duplicated or appears in both the update and archive lists |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - An active customer of this kind already has that name |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - A contact named for update is missing, archived, or belongs to another business - Unexpected error |

### 5. User Manages a Business's Contacts

**Who can reach this step: an owner or admin of the organization.**

- User manages the people they deal with inside a business as part of that business's update.
- Only businesses have contacts.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User adds contacts to an active business | - The contacts are appended to the ones already there - Existing contacts are untouched |
| 2. User adds a contact whose email or phone number another contact at that business already has | - Accepted - Contacts may share email and phone values |
| 3. User adds a contact with no email and no phone number | - Accepted. Any number of contacts may have neither |
| 4. User changes an existing contact | - Only the supplied fields change - Fields left out stay as they were |
| 5. User archives contacts | - The named contacts become archived permanently and keep their details |
| 6. User updates a contact that is missing, archived, or belongs to another business | - The whole business update is rejected and no change is stored |
| 7. User archives a contact that is missing, archived, or belongs to another business | - Nothing changes for that contact and the request still succeeds |
| 8. User manages contacts on an archived or missing business | - Nothing happens, and it is reported as successful - See [gap 2](#2-changes-to-an-archived-customer-are-silently-discarded) |
| 9. A contact ID is repeated in either list, or appears in both update and archive lists | - Rejected with a validation error before any change is stored |

#### Requirements

1. Contacts belong to a business and are added to it, never created on their own.
2. Adding appends. It never replaces the contacts already recorded.
3. Contacts at the same business may share email addresses and phone numbers, whether they are active or archived. Contacts with neither are always allowed.
4. Updating a contact changes only supplied fields. Archiving a contact is permanent and keeps its details.
5. All contact changes and business-field changes in one update are applied atomically.
6. Archiving a business keeps its contacts and their states.
7. Contact IDs are unique within each list and across the update and archive lists.

#### Request / Response / Outcome

All contact lists are part of the business update described in [step 4](#4-user-updates-a-customer). They are optional and default to empty.

**Request** — inserting contacts

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contacts Insert | `CustomerBusinessContactInsert[]` | Empty by default | ❌ | The people to add as active contacts. Same shape as when adding a business, [described in step 1](#1-user-adds-a-customer) |

**Request** — updating contacts

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contacts Update | `CustomerBusinessContactUpdate[]` | Empty by default | ❌ | Existing contacts to change. See **CustomerBusinessContactUpdate** in step 4 |

**Request** — archiving contacts

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contacts Archive | `CustomerBusinessContactArchive[]` | Empty by default | ❌ | Which contacts to archive permanently. See **CustomerBusinessContactArchive** in step 4 |

**Response**

Response is empty.

**Outcome**

- Adding stores the new contacts alongside the existing ones.
- Updating changes the named active contacts and leaves all other contacts untouched.
- Archiving keeps the named contacts but removes them from active-contact use.
- If the business is archived or missing, nothing is stored or changed and the answer still reports success.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more contacts are invalid - A contact ID is duplicated or appears in both the update and archive lists |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - A contact named for update is missing, archived, or belongs to another business - Unexpected error |

### 6. User Archives a Customer

**Who can reach this step: an owner or admin of the organization.**

- User retires a customer they no longer trade with.
- The customer leaves the book but their record is kept.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User archives an active customer | - The customer becomes archived - They disappear from the customer book - Their contacts are kept - Their name becomes free for a new active customer of the same kind |
| 2. User archives a customer that is already archived, or does not exist | - Nothing happens, and it is reported as successful |
| 3. User wants an archived customer back | - Not possible. There is no way to reverse archiving - See [gap 3](#3-archiving-is-final-and-archived-customers-cannot-be-found-again) |

#### Requirements

1. Archiving works the same for a person and a business.
2. An archived customer and its contacts are kept in full but cannot be opened through customer details.
3. Archiving frees the name for reuse by a new active customer of the same kind.
4. Archiving cannot be undone.

#### Request / Response / Outcome

**Request**

The customer identifier is supplied in the request path. There is no request body.

**Response**

Response is empty, whether the customer was archived just now, was already archived, or never existed.

**Outcome**

- An active customer becomes archived and leaves the book. Their record and contacts are kept.
- Their name is released, so a new active customer of the same kind may take it.
- An already-archived or missing customer is left exactly as it was.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 7. User Checks Whether a Customer Name Is Free

**Who can reach this step: any member of the organization.**

- Before adding or renaming a customer, the user may check whether the name is available for that kind of customer.
- The check reserves nothing. A later add or update can still lose a race and return a conflict.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User checks a name that no active customer of that kind uses | - The name is reported as free |
| 2. User checks a name used by an active customer of the same kind | - The name is reported as taken |
| 3. A customer of the other kind uses the name | - The name is still reported as free |
| 4. Only archived customers use the name | - The name is reported as free |
| 5. User checks the current name while updating that customer | - The current customer is ignored, so the name is reported as free unless another active customer of the same kind uses it |
| 6. User omits the name or customer kind, or supplies an invalid name | - The request is rejected with a validation error |
| 7. User supplies an unknown customer kind | - The request is rejected while the request is read |

#### Requirements

1. The check uses the same active, same-kind uniqueness rule as adding and updating.
2. A person and a business may have the same name.
3. Archived customers do not reserve names.
4. An optional customer identifier excludes that customer from the check, for use while updating.
5. The check does not reserve the name or change stored data.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Name | `String` | 1–255 characters, trimmed | ✅ | The person or business name to check |
| Customer Type | `CustomerType` | `Individual` or `Business` | ✅ | Which kind of customer to check |
| Customer ID | `UUID` | Canonical 36-character form | ❌ | The customer being renamed, which is ignored by the check |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Is Name Unique | `Boolean` | — | ✅ | Whether an active customer of this kind already uses the name |

**Outcome**

Nothing changes and the check reserves nothing.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request - The customer kind is not `Individual` or `Business` |
| 400 | `VALIDATION_ERROR` | - The name or customer kind is missing, or the name is invalid |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 8. User Extracts Customers from an Image or File

**Status: available.** The rules below for how the AI reads an image or file, and combines a spreadsheet's general notes, have so far been checked only against a stand-in for the AI service, not the real one; until they are, treat them as intended behaviour. A file without a label row loses its first customer ([gap 6](#6-a-file-without-a-label-row-skips-its-first-customer)).

**Who can reach this step: an owner or admin of the organization.**

- User already keeps a customer list somewhere else — on paper or in a spreadsheet — and wants it in the book without retyping it.
- There are three ways in: an image of something on paper (a business card, a printed grid, a handwritten note), a CSV file, or an Excel file (`.xls` or `.xlsx`).
- The user may add a short message to guide how the image or file is read.
- An AI model reads the image or file.
- The user gets back candidate people and businesses to look over, each saying which of its details were worked out, are missing, or are duplicated, marked when it needs attention, and carrying its own notes, plus processing notes on how many entries were found versus turned into candidates and general notes about the source.
- Nothing is stored. Candidates the user wants to keep are added the normal way, in [step 1](#1-user-adds-a-customer).

This step helps a business move its existing customer list into the product quickly, with as little retyping as possible.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User sends an image, CSV file or Excel file with several clear entries | - Each comes back as a candidate person or business, whichever the AI judges it to be |
| 2. An entry has a name, but other details are unclear or missing | - Still a candidate, with a short note saying what was unclear - Optional details that the source simply does not show never mark it as needing attention |
| 3. An entry has content, but no readable name, no email a name can be worked out from, and no usable customer detail | - Not a candidate, but counted as missed - Its row number appears in the unidentified-rows info note; for an image, this is the AI's best estimate of its position (requirement 5) |
| 4. An entry has no readable name at all, but an email a name can be worked out from | - For a person or a business contact, an email that looks like a person's name, such as `john.smith@example.com`, gives the name; never a role mailbox like `info@…` - For a business, one of its own emails on a company domain, such as `orders@acme-tools.com`, gives `Acme Tools`; never free email providers such as Gmail, Outlook, Hotmail, Yahoo or iCloud, and never a contact's email - The name is listed as an inferred field, the AI notes how it was worked out, and the candidate is not marked as needing attention - Never when any part of the name was readable, unless the processing message asks for it (scenario 28) |
| 5. Two candidates of the same kind in the same image or file share exactly the same name, as written when adding a customer (capital letters count) | - Both are marked as needing attention, each listing its name as a duplicate field - They are not merged - A person and a business with the same name are not duplicates - Candidates without a name are never duplicates - Never compared with customers already in the book |
| 6. Some entries could not be turned into candidates | - The first info note shows how many entries were processed out of the total, such as `Processed 3 of 4 entries` |
| 7. The image or file has nothing recognizable as a customer | - No candidates, and the first info note reads `Processed 0 of 0 entries`, or `Processed 0 of <n> entries` when the source has empty or unidentified rows - Not an error - The general extraction notes explain that the AI could not detect relevant customer data in the image or file |
| 8. The file's name is missing, malformed, has an unsupported extension, or does not match the actual content | - Rejected with `400 BAD_REQUEST_ERROR` - The file is never sent to the AI |
| 9. The file cannot really be read as a CSV or Excel file, or is larger than 20 MB | - Rejected with a server error |
| 10. The AI service has a temporary failure that does not clear | - A server error and no candidates |
| 11. The AI service rejects the request or sends back something unusable | - A server error |
| 12. A member with the ordinary user role tries this step | - Rejected, the same as adding a customer |
| 13. User reads the same image or file again | - Allowed freely; nothing is ever stored |
| 14. The source shows a contact person for a business | - The business candidate includes that contact only when it is on the business's own entry or row, under a clear contact label or grouping, or, in a spreadsheet, on a following row with no name of its own that only adds contact details - An uncertain link is noted - A separate entry with its own name is never made a contact just because it shares the company name |
| 15. User uploads an Excel workbook with several sheets | - Only the first sheet is read; the others are never mentioned |
| 16. User uploads a legacy `.xls` file | - Read the same way as `.xlsx` |
| 17. A spreadsheet row is completely blank | - Never a candidate, but counted in the total of the first info note - Its row number appears in the empty-rows info note, such as `Empty rows (1) at [6]` |
| 18. User uploads a spreadsheet with a very large number of rows | - Every row is read, in groups (requirement 14) - Row numbers and duplicate-name checks still refer to the whole file - General notes that say the same thing are combined (requirement 22) |
| 19. User sends a supported file whose name matches it | - Accepted and read - The name's extension is matched ignoring capitalisation |
| 20. An entry has one address with at least one non-empty text field | - It comes back as both shipping and billing (`ShippingAndBilling`), whatever the source says (requirement 16) - Missing or unclear fields are noted |
| 21. An address has some text fields missing, but at least one field has content | - The partial address is returned with a note saying what was missing or unclear - The rest of the read carries on |
| 22. The source shows an address, but all five of its text fields are empty or unreadable | - That address is left out, and the AI notes that an empty address was left out - The candidate is still returned and is not marked as needing attention - If no address has any non-empty text field, the candidate's address list is empty - Empty address cells, or no address at all, are simply absent |
| 23. An entry has two or more distinct addresses with at least one non-empty text field | - The first two are kept, one shipping and one billing (requirement 16) - Each address left out gets its own note, such as `Address left out: 12 Main Street` - None of this marks the candidate as needing attention |
| 24. The same address appears more than once for one customer | - Identical addresses are merged before requirement 16 picks and types them - Addresses belonging to different customers are not merged |
| 25. The source shows a phone number, for a customer or a contact, but its country code cannot be read | - The code is worked out from context, or guessed when the clues disagree, and listed as an inferred field (requirement 19) - Only when no country is plausible is the number kept without a code, listed as missing, and the candidate marked as needing attention - A phone number whose number itself cannot be read is left out and noted, and the candidate is not marked |
| 26. The source shows an email, for a customer or a contact, that cannot be read | - That email is left out with a note - The rest of the entry is kept, and the candidate is not marked as needing attention |
| 27. A business shows a contact with no readable name, and no email that looks like a person's name | - If the contact has a readable role, email or phone number (a readable national number is enough, even without a country code), it stays in the list without a name - The contact's name is listed as a missing field with the contact's position, and the business is marked as needing attention - With none of those, there is no contact and no note |
| 28. User supplies a processing message | - It takes priority over the default reading rules, within the limits of requirement 24 - It may, for example, split one row into several customers, or complete a missing last name from the entry's email as an inferred field - A detail it asks to leave out is meant to count as absent, not missing; today a dropped country code or name is still reported missing ([gap 7](#7-a-detail-dropped-on-request-is-still-reported-missing)) |
| 29. The message is empty, whitespace-only, ambiguous, or conflicts with the source | - An empty message is treated as no message - Otherwise it is applied as far as the source supports; anything it could not settle is described in the notes - The request is not rejected only for being ambiguous |
| 30. The processing message is longer than 2,000 characters after trimming | - Rejected with `400 BAD_REQUEST_ERROR` - The file is never sent to the AI |
| 31. A candidate has several email addresses or phone numbers | - The first in each list is marked as the default - When the source clearly marks one as the main one, it is listed first and so becomes the default; otherwise the source order decides |
| 32. An entry has no readable name and no email a name can be worked out from, but has at least one usable customer detail, such as a phone number with a readable national number even without a country code | - It comes back as a candidate without a name, as the person or business the AI judges it to be - Its name is listed as a missing field, and it is marked as needing attention - It counts as processed |
| 33. An entry shows only a name | - It comes back as a candidate with no other details, marked by the usual rules - Unless it is clearly a section heading that groups the entries below it - When unsure, it is kept with a note |
| 34. The source has titles, the uploader's own letterhead, dates, column label lines, repeated labels, totals, page numbers or footers | - None of these is an entry: not counted, and in neither row note - The AI notes each one in the general extraction notes |
| 35. A file column, or a kind of detail in an image, has no matching customer field, such as a balance, comment, birthday or loyalty tier | - Left out, and noted once in the general extraction notes, not per row or candidate - Columns that are completely empty are skipped |
| 36. A file's first row looks like a customer rather than column labels | - It is used only to understand the columns - It is not read as a customer and not counted - One general note says the first row looked like data and was skipped, so the user can add that customer by hand - See [gap 6](#6-a-file-without-a-label-row-skips-its-first-customer) |
| 37. A phone number's country code is written with it, such as `+357` or `00357`, or in its own dialling-code column | - The country code counts as read, not inferred |
| 38. An entry's name is in another language, or its kind is not stated | - Names are kept as written, never translated - Person or business is decided from evidence: a company name, a legal suffix, a tax ID, or listed contacts |
| 39. The processing message makes entries give several candidates, such as "for each entry create 2 candidates" | - Each such entry still counts once as processed - A second info note, such as `Candidates created 440`, gives the number of candidates - That note is left out when every processed entry gave exactly one candidate |

#### Requirements

1. Every candidate carries the same details as a person or business added in [step 1](#1-user-adds-a-customer), whatever the source, plus its inferred, missing and duplicate fields, whether it needs attention, and its own extraction notes, and the AI decides which kind each entry is.
2. A candidate's name, and any contact's name, may be absent but is never empty when returned, and every other returned detail follows its field rule from step 1, or the whole read fails with a server error. Which addresses are returned is set out in requirement 15.
3. Whether a phone number is real for its country, and whether an email meets the full email standard, is only checked when a candidate is actually added; until then an email is returned as read, not lower-cased. A candidate's addresses are always an allowed combination (functional rule 6), because the system sets their number and types (requirement 16). The first email and the first phone number in each of a candidate's lists are marked as the default by the system, so the exactly-one-default rule never fails.
4. When no name at all can be read, a name may be worked out from an email, listed as an inferred field, noted by the AI, and counted as an ordinary candidate that does not need attention: a person's or a contact's name from an email that plainly looks like a person's name, never a role mailbox or a meaningless address; a business's own name from the company domain of one of its own emails, never a free email provider, a meaningless or generic domain, or a contact's email. Never when any part of the name was readable, unless the processing message asks for it (requirement 24).
5. An entry is one original customer row of a file, or one visible entry of an image, and counts once as processed when it gives at least one candidate, with or without a name. The info notes are, in order, `Processed <processed> of <total> entries`, then `Candidates created <count>` only when the candidates differ from the processed entries, then `Empty rows (<count>) at [<rows>]` and `Unidentified rows (<count>) at [<rows>]` only when they have rows, using the row numbers the person sees in their own file (the label row is row 1). The total is processed plus empty plus unidentified entries, so a file with 347 rows below the label row, one of them empty, reads `Processed 346 of 347 entries`. An image has no empty rows, and its row positions are the AI's top-to-bottom estimate, starting at 1. An unidentified row has content but no readable name or usable customer detail, or, in an image, is an entry too unclear to return.
6. Candidates of the same kind whose names are exactly the same, with capital letters counting as they do when a customer is added, are each marked as needing attention across the whole image or file, but never compared with customers already in the book. Only candidates with a name are compared.
7. Only PNG, JPEG or WEBP images and files genuinely readable as CSV or Excel (`.xls` or `.xlsx`) are accepted, up to 20 MB, with no limit on the number of rows.
8. The file's name is required, 1–255 trimmed characters, and must end in `.png`, `.jpg`, `.jpeg`, `.webp`, `.csv`, `.xls` or `.xlsx` matching the actual file, ignoring capitalisation, or the request is rejected with `400 BAD_REQUEST_ERROR` and the file is never sent to the AI.
9. Nothing is ever kept — no customer, no contact, and no copy of the image or file — and a candidate only becomes a customer once added through [step 1](#1-user-adds-a-customer).
10. A business candidate includes a contact only when it is on the business's own entry or row, under a clear contact label or grouping, or, in a spreadsheet, on a following row with no name of its own that only adds contact details. An uncertain link is noted, and a separate named entry is never made a contact because it shares the company name.
11. Only the first sheet of an Excel workbook is read.
12. A completely blank spreadsheet row is never a candidate, but it counts towards the total in the first info note and its row number appears in the empty-rows info note.
13. When the AI service cannot be reached, or its answer cannot be used, the read ends with a server error and no candidates.
14. Large spreadsheets are read in groups of up to 100 rows, up to 4 groups at a time, and if any group fails the whole read fails with no partial candidates.
15. An address is returned when at least one of its five text fields has content, even if it is partial, with a note for missing or unclear fields; an address whose five fields are all blank is left out, and a candidate with none left keeps an empty address list.
16. The AI marks each address as shipping or billing as best it can, and the system keeps the first two non-empty ones: one alone is `ShippingAndBilling`; two stay as marked when they are one `Shipping` and one `Billing`, in either order, and otherwise the first becomes `Shipping` and the second `Billing`. Each address left out gets one note, `Address left out: <value>`, using its first non-empty field of address line 1, address line 2, city, postal code and country, in that order; setting a type adds no note.
17. Every candidate lists its inferred fields (worked out rather than read), its missing fields (absent), and its duplicate fields (its name matches another candidate of the same kind in the same upload), each as a path to that value in the candidate, listed under **FieldInfo** below. The client uses each path to mark the matching input red (missing or duplicate) or yellow (inferred).
18. A candidate needs attention when it has any missing or duplicate field: its name is missing (requirement 26) or duplicated (requirement 6), a contact's name is missing, or a phone number, its own or a contact's, has no country code. Inferred fields never mark a candidate.
19. A country code written with the number, such as `+357` or `00357`, or in its own dialling-code column, counts as read. A missing code may be worked out from clear context, such as the entry's own address or a country used across the source, and is then listed as an inferred field and noted. When the clues conflict, the AI still gives its most plausible code, trusting the number's length and format and the country used across the source or the processing message over the address, and notes that the guess does not match, such as `Country code +357 guessed, but the city is in Greece`. Only when no country is plausible is the number kept without a code, the code listed as missing, and the reason noted.
20. A detail that cannot be read is left out and noted, and never marks a candidate: an unreadable email, a phone number whose number cannot be read, or an address whose five text fields are all empty. Details the source does not show, such as empty cells or columns, are simply absent. Partial addresses and addresses left out because only two are kept never mark a candidate either. A detail left out at the processing message's request is meant to be absent rather than missing, but today a dropped country code or name is still reported missing ([gap 7](#7-a-detail-dropped-on-request-is-still-reported-missing)).
21. Each candidate's extraction notes are the AI's short observations, such as `An email address could not be read`, plus one system note per address left out, in this order: about the candidate, about its addresses, the left-out address notes, then about each contact; they are never de-duplicated. Every note the AI writes, here and in the general extraction notes, has at most ten words, one point, plain everyday words and no semicolon, such as `Country code +357 added, based on the other numbers`.
22. The general extraction notes are the AI's comments about the source as a whole. For a spreadsheet read in two or more groups, notes that say the same thing are combined into one, and every other note is kept exactly as written, in file order. If combining fails, each group's notes come back unchanged, so a note may repeat, and the read still succeeds.
23. An optional processing message is 1–2,000 characters after trimming; an empty or whitespace-only message counts as none, and a longer one is rejected with `400 BAD_REQUEST_ERROR` before the file is sent to the AI.
24. The processing message applies to the whole upload and every spreadsheet group, and may change anything about how the source is read, taking priority over the default reading rules, including how details are worked out and how unreadable details are handled. Three things stay fixed: every result still fits the response's rules, and a request that cannot fit is applied as far as possible and explained in a note; it never adds details the source does not support; and the system still works out the counts, the duplicate and missing fields and needs-attention, so the message cannot hide missing or unclear data. An entry it asks to skip is counted nowhere, not even as unidentified; a detail it asks to leave out is covered by requirement 20. It is never stored or returned.
25. When the source clearly marks one email address or phone number as the main one, the AI lists it first, so it becomes the default; otherwise each list keeps the source order.
26. When no name can be read or worked out from an email, the AI is asked to still return the entry as a candidate, without a name, if it has at least one usable customer detail: a readable email, a phone number with a readable national number (a country code is not needed), an address with at least one non-empty text field, a tax ID, or a contact. It is asked to keep a contact without a name only if the contact has a readable role, email or phone number, where a phone number with a readable national number counts even without a country code; otherwise there is no contact and no note.
27. An entry with only a name is a candidate with no other details, unless it is clearly a section heading that groups the entries below it; when unsure, it is kept with a note.
28. Titles, the uploader's own letterhead, dates, column label lines, repeated labels, totals, page numbers and footers are not entries: they are not counted, appear in neither row note, and the AI notes each one in the general extraction notes.
29. A file column, or a kind of detail in an image, with no matching customer field is left out and noted once in the general extraction notes, never per row or candidate. Columns that are completely empty are skipped.
30. A file's first row is always treated as the label row. When it looks like a customer instead, it is used only to understand the columns, is not read as a customer or counted, and gets one general note saying it looked like data and was skipped, so the user can add that customer by hand ([gap 6](#6-a-file-without-a-label-row-skips-its-first-customer)).
31. Names are kept as written, never translated. Whether an entry is a person or a business is decided from evidence: a company name, a legal suffix, a tax ID, or listed contacts.

#### Request / Response / Outcome

One upload carries whichever of the three the user has — an image, a CSV file, or an Excel file — in the same request shape; the server works out which it was sent.

**Request**

The body is the image or file itself, as with every other upload in this product. The organization travels in a header, since the body carries the file. The file's name is a required header; its extension must match the actual file, and it is never stored. The processing message is an optional header and is never stored either.

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form | ✅ | Which organization's book this is for. Travels in the request's header |
| File | Binary | PNG, JPEG or WEBP; or genuinely readable as CSV or Excel (`.xls` or `.xlsx`); up to 20 MB | ✅ | The image or file to read, sent as the request body |
| File Name | `String` | 1–255 characters, trimmed; supported extension matching the actual format | ✅ | The file's name, sent as a header. It is never stored |
| Processing Message | `String` | 1–2,000 characters after trimming; empty or whitespace-only counts as none | ❌ | Optional instructions for reading the uploaded source, which take priority over the default reading rules (requirement 24), sent as a header. It is never stored |

**Response — `CustomerBookFileAiExtractPostResponse`**

In this response, a list with nothing in it is left out rather than sent empty, so a client treats a missing list as empty. That is why those lists are marked not required below.

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Individual Candidates | `CustomerIndividualCandidate[]` | Left out when empty | ❌ | Recognized people, in whatever order the source listed them |
| Business Candidates | `CustomerBusinessCandidate[]` | Left out when empty | ❌ | Recognized businesses, in whatever order the source listed them |
| Info Notes | `String[]` | Always starts with the processed-of-total summary; a candidates-created count only when it differs from the processed entries; empty-row and unidentified-row summaries only when they have rows | ✅ | Processing summaries for this source |
| Extraction Notes | `String[]` | Left out when empty; plain strings, one point each, no bullet prefix | ❌ | The AI's general comments about the source, including when no customer data could be detected; for a spreadsheet read in two or more groups, notes that say the same thing are combined into one, but may repeat if combining fails (requirement 22) |

**CustomerIndividualCandidate**

The details follow the same field rules as **CustomerBookIndividualPostRequest** ([step 1](#1-user-adds-a-customer)). The checks in requirement 3 are left until the candidate is added.

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed | ❌ | The person's name, as read or worked out from an email; absent when not readable, which marks the candidate |
| Emails | `CustomerEmailEntry[]` | Left out when empty; the first entry is the default | ❌ | Readable email addresses; one that cannot be read is left out and noted |
| Phone Numbers | `CustomerPhoneNumberEntryCandidate[]` | Left out when empty; the first entry is the default | ❌ | Phone numbers as read. See **CustomerPhoneNumberEntryCandidate** below |
| Addresses | `CustomerAddressEntry[]` | Left out when empty; otherwise one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Addresses as read, at most two, each with its type |
| Inferred Fields | `FieldInfo[]` | Left out when empty | ❌ | Details worked out rather than read. See **FieldInfo** below |
| Missing Fields | `FieldInfo[]` | Left out when empty | ❌ | Details that are absent and must be supplied before adding |
| Duplicate Fields | `FieldInfo[]` | Left out when empty | ❌ | Details that match another candidate of the same kind in the same upload |
| Needs Attention | `Boolean` | True exactly when Missing Fields or Duplicate Fields is non-empty | ✅ | Whether the user must fix something before adding |
| Extraction Notes | `String[]` | Left out when empty; plain strings, one point each, no bullet prefix | ❌ | The AI's own observations about the person, then about each of its addresses, then a note for each address left out |

**FieldInfo**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Path | `String` | A JSON Pointer ([RFC 6901](https://www.rfc-editor.org/rfc/rfc6901)) from the candidate object, with 0-based list positions; one of `/fullName`, `/businessName`, `/phoneNumbers/{i}/phoneNumber/phoneCountryCode`, `/customerBusinessContacts/{i}/fullName`, `/customerBusinessContacts/{i}/phoneNumber/phoneCountryCode` | ✅ | The exact value in the candidate this is about, such as `/phoneNumbers/1/phoneNumber/phoneCountryCode` for the second phone's country code |

**CustomerPhoneNumberEntryCandidate**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Phone Number | `CustomerPhoneNumberCandidate` | — | ✅ | One phone number. See **CustomerPhoneNumberCandidate** below |
| Is Default | `Boolean` | True for the first entry in the list only | ✅ | Marks the number to use by default |

**CustomerPhoneNumberCandidate**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Phone National Number | `String` | 1–255 characters, trimmed | ✅ | The number without its country code |
| Phone Country Code | `String` | 1–255 characters, trimmed | ❌ | The country dialling code, as read, or worked out or guessed from context; absent only when no country is plausible, which marks the candidate |

**CustomerBusinessCandidate**

The details follow the same field rules as **CustomerBookBusinessPostRequest** ([step 1](#1-user-adds-a-customer)), handled the same way as **CustomerIndividualCandidate**.

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Business Name | `String` | 1–255 characters, trimmed | ❌ | The company's name, as read or worked out from its email domain; absent when not readable, which marks the candidate |
| Emails | `CustomerEmailEntry[]` | Left out when empty; the first entry is the default | ❌ | Readable email addresses; one that cannot be read is left out and noted |
| Tax ID | `String` | 1–255 characters, trimmed | ❌ | The company's tax reference, when readable |
| Phone Numbers | `CustomerPhoneNumberEntryCandidate[]` | Left out when empty; the first entry is the default | ❌ | Phone numbers as read |
| Addresses | `CustomerAddressEntry[]` | Left out when empty; otherwise one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Addresses as read, at most two, each with its type |
| Customer Business Contacts | `CustomerBusinessContactCandidate[]` | Left out when empty; best-effort | ❌ | People inside the business, when the source identifies them. See **CustomerBusinessContactCandidate** below |
| Inferred Fields | `FieldInfo[]` | Left out when empty | ❌ | Details of the business or its contacts worked out rather than read. See **FieldInfo** above |
| Missing Fields | `FieldInfo[]` | Left out when empty | ❌ | Details of the business or its contacts that are absent and must be supplied before adding |
| Duplicate Fields | `FieldInfo[]` | Left out when empty | ❌ | Details that match another business candidate in the same upload |
| Needs Attention | `Boolean` | True exactly when Missing Fields or Duplicate Fields is non-empty | ✅ | Whether the user must fix something before adding |
| Extraction Notes | `String[]` | Left out when empty; plain strings, one point each, no bullet prefix | ❌ | The AI's own observations about the business, then about each of its addresses, then a note for each address left out, then the AI's observations about each contact |

**CustomerBusinessContactCandidate**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed | ❌ | The contact's name, as read or worked out from an email; absent when not readable, which marks the business |
| Role | `String` | 1–255 characters, trimmed | ❌ | What they do there, when readable |
| Email | `String` | Shaped like `name@domain.tld`, with no spaces; max 255 characters; the full email standard is checked only when added (requirement 3) | ❌ | The contact's email, when readable; one that cannot be read is left out and noted |
| Phone Number | `CustomerPhoneNumberCandidate` | — | ❌ | The contact's phone number, when its number is readable; its country code may be absent, which marks the business |

Examples: `Processed 12 of 16 entries`, `Empty rows (2) at [3, 5]`, and `Unidentified rows (2) at [7, 11]` are separate info notes; one row reads `Empty rows (1) at [6]` or `Unidentified rows (1) at [7]`. When the processing message turns 346 entries into 440 candidates, the notes read `Processed 346 of 347 entries`, `Candidates created 440` and `Empty rows (1) at [6]`. An image with 3 candidates and two entries it could not read reads `Processed 3 of 5 entries` and `Unidentified rows (2) at [4, 9]`. `The image is cropped` belongs in the general extraction notes, and `An email address could not be read` in a candidate's extraction notes, as does `Address left out: 12 Main Street` for a third address with line 1 `12 Main Street` and city `Nicosia`, or `Address left out: Nicosia` for one with only a city. A business whose name matches another business candidate lists `/businessName` as a duplicate field; a person without a name lists `/fullName` as a missing field; a business whose second contact has no name lists `/customerBusinessContacts/1/fullName` as a missing field; a person whose first phone number's country code was worked out from their address lists `/phoneNumbers/0/phoneNumber/phoneCountryCode` as an inferred field, and so does a person whose 8-digit number `96334221` was given `+357` even though their city is `Θεσσαλονίκη`, with a note saying the code is a guess that does not match the city. Only missing and duplicate fields mark a candidate as needing attention; inferred fields and notes never do.

**Outcome**

- Nothing is stored: no customer, no contact, no processing message, and no copy of the image or file. The response is not remembered either.
- The image or file is read by an outside AI service and not kept afterwards.
- A missing, malformed or mismatched file name, or a processing message over 2,000 characters, stops the request before the file is sent to the AI.
- Each candidate shows which of its details were worked out, which are missing (a name, a contact's name, or a phone country code), and whether its name is duplicated, so the user can fix it first, and its notes explain anything that was left out. An entry with usable details but no readable name still comes back, without a name, for the user to complete.
- Using a candidate means adding it through [step 1](#1-user-adds-a-customer), like anything typed in by hand, where every check is made.
- The first info note shows when entries were missed — say `Processed 3 of 4 entries` — and the row notes point straight to them; for an image the positions are the AI's best estimate. A `Candidates created` note appears only when the number of candidates differs from the entries processed.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization id header is missing - The required file name header is missing or malformed - The filename extension is missing, unsupported, or does not match the actual file, including a file that is not a supported image, CSV or Excel file - The processing message is longer than 2,000 characters after trimming |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow this |
| 500 | `INTERNAL_SERVER_ERROR` | - The upload could not be read, or is larger than 20 MB - The file is not genuinely readable as a CSV or Excel file - The person does not belong to the organization - The AI service could not be reached, or sent back something that could not be used - Unexpected error |

### Known gaps and open questions

Everything above describes what the product does today. Nothing in this section exists yet; each one needs a product answer before it can be built.

#### 1. Looking up an unavailable customer is reported as a server error

Opening a customer that does not exist, has been archived, or asking for a person through the business screen, comes back as a server error rather than "not found".

These are ordinary cases: a stale link, a bookmark to something since archived, or simply the wrong screen for that kind of customer. The person is told the product is broken.

This is the same shape as [Organization Onboarding gap 3]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#3-not-belonging-to-an-organization-is-reported-as-a-server-error), which applies here too: on every step, naming an organization the person does not belong to is a server error. The two should be answered together.

**To decide:** whether a missing or wrong-kind customer should be a plain "not found".

#### 2. Changes to an archived customer are silently discarded

Changing an archived customer, or adding and removing its contacts, does nothing at all — and reports success. The same happens for a customer that does not exist.

Nothing distinguishes "saved" from "quietly ignored". Someone who already has a customer's screen open when a colleague archives it can submit a change and see it accepted, then find it gone.

The reasoning is that archiving already achieves what the edit wanted. That holds for archiving something twice; it does not hold for someone typing a new phone number into a form.

**To decide:** whether editing an archived customer should say so. The customer can no longer be opened after archiving, but a screen already open may still be submitted.

#### 3. Archiving is final, and archived customers cannot be found again

Archiving cannot be undone, the customer book lists only current customers, and archived customer details cannot be opened. There is no way to browse or search archived ones. Customer and contact details remain stored.

Together that means an accidental archive is unrecoverable in practice: the stored record cannot be reached through the product.

**To decide:** whether archiving can be reversed, and whether archived customers should be listable. If reversing is allowed, restoring a customer whose name has since been taken by a new active one needs an answer.

#### 4. Nothing prevents a contact being attached to a person

Contacts belong to businesses, but only the product's own screens and requests keep it that way; the stored data itself would accept a contact on a person.

Nothing today creates such a record, but a future change or a direct data fix could leave a person carrying contacts no screen would ever show.

**To decide:** whether the rule should be enforced where the data is kept, rather than only in the code path that happens to write it.

#### 5. Nothing limits how often an image or file can be read

[Extracting customers from an image or file](#8-user-extracts-customers-from-an-image-or-file) can be used as often as an owner or admin likes, with no limit per person, per organization, or overall. Each read goes to an outside AI service, which costs money, so nothing stops it being used far more than the migration it is meant for needs.

**To decide:** whether a limit is needed, and if so what it should be.

#### 6. A file without a label row skips its first customer

When [extracting customers from a file](#8-user-extracts-customers-from-an-image-or-file), the first row is always treated as the label row. In a file without labels, that first customer is skipped with a note.

The user has to add that customer by hand.

**To decide:** whether files without a label row should be detected, so the first customer is read.

#### 7. A detail dropped on request is still reported missing

When [extracting customers](#8-user-extracts-customers-from-an-image-or-file) with a processing message that asks for phone country codes to be left out, each number comes back without its code but is still listed as missing, and the candidate is marked as needing attention. A name, or a contact's name, that the message asks to leave out is treated the same way. Scenario 28 and requirements 20 and 24 intend such a detail to count as absent, so it would not mark the candidate.

Every such candidate then looks as though it needs fixing, and it cannot be added until the user supplies the very detail they asked to drop.

**To decide:** whether a detail dropped on request should stop counting as missing, and if so how the system should tell it apart from one that could not be read.

{% include abbreviations.md %}
