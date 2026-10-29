---
title: Customer Book
---

# Customer Book

### Overview

Every organization keeps a book of the people and companies it trades with. It is the organization's own address book, and it is where future orders will point.

Customer-record actions use the `/customer-book/` resource namespace. Reading an image or file remains at `/extract/customers`.

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
| One person | One `CustomerIndividual` |
| One business | One `CustomerBusiness` |
| A mixed batch | `CustomerIndividual[]` and `CustomerBusiness[]` together |

The two shapes are separate and neither is a variant of the other. A person has a name and no tax id and no contacts; a business has a business name and may have both.

**Request — CustomerIndividual**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed | ✅ | The person's name |
| Emails | `EmailEntry[]` | Empty by default | ❌ | Contact email addresses. See **EmailEntry** below |
| Phone Numbers | `PhoneNumberEntry[]` | Empty by default | ❌ | Contact numbers. See **PhoneNumberEntry** below |
| Addresses | `AddressEntry[]` | Empty by default; otherwise one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Where they are. See **AddressEntry** below |

**Request — CustomerBusiness**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Business Name | `String` | 1–255 characters, trimmed | ✅ | The company's name |
| Emails | `EmailEntry[]` | Empty by default | ❌ | Contact email addresses. See **EmailEntry** below |
| Phone Numbers | `PhoneNumberEntry[]` | Empty by default | ❌ | Contact numbers. See **PhoneNumberEntry** below |
| Tax ID | `String` | 1–255 characters, trimmed | ❌ | The company's tax reference. A person may never have one |
| Customer Business Contacts | `BusinessContact[]` | Empty by default | ❌ | People inside the business. See **BusinessContact** below |
| Addresses | `AddressEntry[]` | Empty by default; otherwise one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Where they are. See **AddressEntry** below |

The shapes used above and throughout this epic:

**EmailEntry**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854); max 255 characters | ✅ | One contact email address |
| Is Default | `Boolean` | Exactly one entry in the list must be true | ✅ | Marks the email address to use by default |

**PhoneNumberEntry**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Phone Number | `PhoneNumber` | — | ✅ | One contact number. See **PhoneNumber** below |
| Is Default | `Boolean` | Exactly one entry in the list must be true | ✅ | Marks the number to use by default |

**PhoneNumber**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Phone National Number | `String` | 1–255 characters, trimmed; must be a real number for its country | ✅ | The number without its country code |
| Phone Country Code | `String` | 1–255 characters, trimmed; must be a real country dialling code | ✅ | The country dialling code |

**AddressEntry**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Address | `Address` | — | ✅ | Where it is. See **Address** below |
| Address Type | `AddressType` | `Shipping`, `Billing` or `ShippingAndBilling` | ✅ | What the address is used for. `ShippingAndBilling` means one address serves both |

**Address**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Address Line 1 | `String` | 1–255 characters, no leading or trailing whitespace when present | ❌ | Street address |
| Address Line 2 | `String` | 1–255 characters, no leading or trailing whitespace when present | ❌ | Street address, continued |
| City | `String` | 1–255 characters, no leading or trailing whitespace when present | ❌ |  |
| Postal Code | `String` | 1–255 characters, no leading or trailing whitespace when present | ❌ |  |
| Country | `String` | 1–255 characters, no leading or trailing whitespace when present | ❌ | Free text, as the person types it |

**BusinessContact**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed | ✅ | The contact's name |
| Role | `String` | 1–255 characters, trimmed | ❌ | What they do there |
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854); max 255 characters | ❌ |  |
| Phone Number | `PhoneNumber` | — | ❌ |  |

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
| Emails | `EmailEntry[]` | May be empty | ✅ | Every recorded email address, each marked default or not |
| Phone Numbers | `PhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Addresses | `AddressEntry[]` | May be empty | ✅ | Every recorded address, each with its type |

**Response — Business**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Identifies the customer |
| Business Name | `String` | — | ✅ | The company's name |
| Emails | `EmailEntry[]` | May be empty | ✅ | Every recorded email address, each marked default or not |
| Tax ID | `String` | — | ❌ | Present when recorded |
| Phone Numbers | `PhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Addresses | `AddressEntry[]` | May be empty | ✅ | Every recorded address, each with its type |
| Customer Business Contacts | `CustomerBusinessContact[]` | May be empty; active contacts only | ✅ | Active business contacts; archived contacts are omitted |

**CustomerBusinessContact**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contact ID | `UUID` | Canonical 36-character form | ✅ | The contact's identifier, used for later updates or archiving |
| Full Name | `String` | — | ✅ | The contact's name |
| Role | `String` | — | ❌ | Present only if recorded |
| Email | `String` | — | ❌ | Present only if recorded |
| Phone Number | `PhoneNumber` | — | ❌ | Present only if recorded |

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

**Request — updating a CustomerIndividual**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed; cannot be cleared | ❌ | Replacement name; leave out to keep the current name |
| Emails | `EmailEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list | ❌ | Send the complete set, not just additions |
| Phone Numbers | `PhoneNumberEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list | ❌ | Send the complete set, not just additions |
| Addresses | `AddressEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list; when non-empty, one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Send the complete set, not just additions |

**Request — updating a CustomerBusiness**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Business Name | `String` | 1–255 characters, trimmed; cannot be cleared | ❌ | Replacement name; leave out to keep the current name |
| Emails | `EmailEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list | ❌ | Send the complete set, not just additions |
| Tax ID | `String` | 1–255 characters, trimmed; explicit null clears it | ❌ | Replacement tax ID; leave out to keep the current value |
| Phone Numbers | `PhoneNumberEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list | ❌ | Send the complete set, not just additions |
| Addresses | `AddressEntry[]` | Nonnullable; omitted keeps; empty clears; non-empty replaces the whole list; when non-empty, one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Send the complete set, not just additions |
| Customer Business Contacts Insert | `BusinessContact[]` | Empty by default | ❌ | Contacts to add as active contacts |
| Customer Business Contacts Update | `BusinessContactUpdate[]` | Empty by default | ❌ | Existing contacts to change |
| Customer Business Contacts Archive | `ContactReference[]` | Empty by default | ❌ | Existing contacts to archive permanently |

The customer identifier is supplied in the path for both forms. Individual updates have no contact lists. Business updates apply all three contact lists together with the business fields.

**BusinessContactUpdate**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contact ID | `UUID` | Canonical 36-character form | ✅ | Which existing contact to change |
| Full Name | `String` | 1–255 characters, trimmed | ❌ | Replacement name; omitted means unchanged |
| Role | `String` | 1–255 characters, trimmed | ❌ | Replacement role; null clears it |
| Email | `String` | Standardised email; max 255 characters | ❌ | Replacement email; null clears it |
| Phone Number | `PhoneNumber` | A real number for its country | ❌ | Replacement phone; null clears it |

**ContactReference**

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
| Customer Business Contacts Insert | `BusinessContact[]` | Empty by default | ❌ | The people to add as active contacts. Same shape as when adding a business, [described in step 1](#1-user-adds-a-customer) |

**Request** — updating contacts

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contacts Update | `BusinessContactUpdate[]` | Empty by default | ❌ | Existing contacts to change. See **BusinessContactUpdate** in step 4 |

**Request** — archiving contacts

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contacts Archive | `ContactReference[]` | Empty by default | ❌ | Which contacts to archive permanently. See **ContactReference** in step 4 |

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

**Who can reach this step: an owner or admin of the organization.**

- User already keeps a customer list somewhere else — on paper or in a spreadsheet — and wants it in the book without retyping it.
- There are three ways in: an image of something on paper (a business card, a printed grid, a handwritten note), a CSV file, or an Excel file (`.xls` or `.xlsx`).
- An AI model reads the image or file.
- The user gets back candidate people and businesses to look over, how many entries were found versus turned into candidates, and a short note on anything worth checking again.
- Nothing is stored. Candidates the user wants to keep are added the normal way, in [step 1](#1-user-adds-a-customer).

This step helps a business move its existing customer list into the product quickly, with as little retyping as possible.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User sends an image, CSV file or Excel file with several clear entries | - Each comes back as a candidate person or business, whichever the AI judges it to be |
| 2. An entry has a name, but other details are unclear or missing | - Still a candidate, with a short note saying what was unclear |
| 3. An entry has no readable name, and no email that looks like a person's name | - Not a candidate, but counted as missed - For a spreadsheet, its row number is listed as unidentified |
| 4. An entry has no readable name at all, but an email that looks like a person's name, such as `john.smith@example.com` | - The name is worked out from the email, for a person or a business contact, and the candidate notes it - Never for role mailboxes like `info@…`, or when any part of a name was readable |
| 5. Two candidates in the same image or file share a name, ignoring capitalisation | - Each is marked as a possible duplicate if they are the same kind - A person and a business with the same name are not duplicates |
| 6. Some entries could not be turned into candidates | - The counts show how many were found and how many became candidates |
| 7. The image or file has nothing recognizable as a customer | - Empty lists, with both counts at zero - Not an error - The notes explain that the AI could not detect relevant customer data in the image or file |
| 8. The file's name is missing, malformed, has an unsupported extension, or does not match the actual content | - Rejected with `400 BAD_REQUEST_ERROR` - The file is never sent to the AI |
| 9. The file cannot really be read as a CSV or Excel file, or is larger than 20 MB | - Rejected with a server error |
| 10. The AI service has a temporary failure | - Tried again up to two more times - If every attempt fails, a server error and no candidates |
| 11. The AI service rejects the request or sends back something unusable | - Not retried - A server error |
| 12. A member with the ordinary user role tries this step | - Rejected, the same as adding a customer |
| 13. User reads the same image or file again | - Allowed freely; nothing is ever stored |
| 14. A spreadsheet row names a contact person for a business | - The business candidate may include that contact, best-effort only |
| 15. User uploads an Excel workbook with several sheets | - Only the first sheet is read; the others are never mentioned |
| 16. User uploads a legacy `.xls` file | - Read the same way as `.xlsx` |
| 17. A spreadsheet row is completely blank | - Not counted as an entry - Its row number is listed as empty |
| 18. User uploads a spreadsheet with a very large number of rows | - Every row is read, in groups of up to 50, with up to 3 groups at a time - If any group fails, the whole read fails with no partial result - Row numbers and duplicate marks still refer to the whole file |
| 19. User sends a supported file whose name matches it | - Accepted and read - The name's extension is matched ignoring capitalisation |
| 20. An entry has one address with at least one non-empty text field | - It always comes back marked as both shipping and billing (`ShippingAndBilling`), whatever the source says - Missing or unclear fields are noted |
| 21. An address has some text fields missing, but at least one field has content | - The partial address is returned with a note saying what was missing or unclear - The rest of the read carries on |
| 22. An address has all five text fields empty | - That address is omitted - If no address has any non-empty text field, the candidate's address list is empty - The candidate itself is still returned |
| 23. An entry has two or more distinct addresses with at least one non-empty text field | - One comes back as shipping and the other as billing, as the source shows - When the source does not say which is which, the first is shipping and the second billing, and the candidate notes that the types were assumed - If there are more than two, the best shipping and billing pair is kept, or else the first two, with a note that further addresses were left out |
| 24. The same address appears more than once for one customer | - Identical addresses are merged before assigning their types or choosing which to keep - If only one distinct address remains, it is marked as both shipping and billing - Addresses belonging to different customers are not merged |

#### Requirements

1. Every candidate has the same shape as a person or business added in [step 1](#1-user-adds-a-customer), whatever the source, and the AI decides which kind each entry is.
2. A candidate's name, and any contact's name, is never empty, and every other returned detail follows its field rule from step 1, or the whole read fails with a server error. The AI instruction explicitly says to return an address when at least one of its five text fields has content, even when the address is partial, and to omit an address when all five fields are empty. Missing or unclear fields in a returned partial address are noted; if no address has any content, the candidate's address list is empty.
3. Whether a phone number is real for its country, whether exactly one default is marked, and whether the addresses are an allowed combination, is only checked when a candidate is actually added.
4. When no name at all can be read but an email plainly looks like a person's name, the name is worked out from that email, noted on the candidate, and counted as an ordinary candidate — never for a role mailbox, a meaningless address, a business's own name, or a name that was partly readable.
5. The response always says how many entries were found and how many became candidates; for a spreadsheet, missed rows are listed by the row numbers the person sees in their own file, and for an image any detail goes in the notes.
6. Candidates of the same kind whose names match, ignoring capitalisation, are each marked as a possible duplicate across the whole image or file, but never compared with customers already in the book.
7. Only PNG, JPEG or WEBP images and files genuinely readable as CSV or Excel (`.xls` or `.xlsx`) are accepted, up to 20 MB, with no limit on the number of rows.
8. The file's name is required, 1–255 trimmed characters, and must end in `.png`, `.jpg`, `.jpeg`, `.webp`, `.csv`, `.xls` or `.xlsx` matching the actual file, ignoring capitalisation, or the request is rejected with `400 BAD_REQUEST_ERROR` and the file is never sent to the AI.
9. Nothing is ever kept — no customer, no contact, and no copy of the image or file — and a candidate only becomes a customer once added through [step 1](#1-user-adds-a-customer).
10. A business candidate may include contacts when the source identifies one, best-effort only and with no required layout.
11. Only the first sheet of an Excel workbook is read.
12. A completely blank spreadsheet row is never counted as an entry, but its row number is listed as empty.
13. Each AI attempt may take up to three minutes by default, and temporary failures are tried again up to two more times, while rejections and unusable answers are not.
14. Large spreadsheets are read in groups of up to 50 rows, up to 3 groups at a time, and if any group fails the whole read fails with no partial candidates.
15. When a spreadsheet read in groups has something worth flagging, the person gets one short combined message, and failing to combine the messages never fails the read.
16. The AI instruction treats missing, null, empty, and whitespace-only address text as empty, and treats any non-whitespace content as present after trimming. It returns each address that has at least one non-empty text field, including partial addresses, and notes missing or unclear fields; it omits each address whose five text fields are all empty. If none remain, the candidate's address list is empty rather than the candidate being dropped.
17. The AI is asked to return at most two non-empty addresses per candidate: one alone is always marked `ShippingAndBilling`; two are marked one shipping and one billing as the source shows, or first shipping and second billing with a note that the types were assumed when it does not say; and from three or more, two are kept — the best shipping and billing pair, or else the first two — with a note that further addresses were left out.

#### Request / Response / Outcome

One upload carries whichever of the three the user has — an image, a CSV file, or an Excel file — in the same request shape; the server works out which it was sent.

**Request**

The body is the image or file itself, as with every other upload in this product. The organization travels in a header, since the body carries the file. The file's name is a required header; its extension must match the actual file, and it is never stored.

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form | ✅ | Which organization's book this is for. Travels in the request's header |
| File | Binary | PNG, JPEG or WEBP; or genuinely readable as CSV or Excel (`.xls` or `.xlsx`); up to 20 MB | ✅ | The image or file to read, sent as the request body |
| File Name | `String` | 1–255 characters, trimmed; supported extension matching the actual format | ✅ | The file's name, sent as a header. It is never stored |

**Response — `ExtractCustomersPostResponse`**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Entries Identified | `Long` | Whole number, zero or more | ✅ | How many entries the source seemed to contain, including ones not turned into a candidate |
| Entries Processed | `Long` | Whole number, zero or more | ✅ | How many of those became a candidate person or business |
| Customer Individual Candidates | `ExtractCustomerIndividualData[]` | May be empty | ✅ | Recognized people, in whatever order the source listed them |
| Customer Business Candidates | `ExtractCustomerBusinessData[]` | May be empty | ✅ | Recognized businesses, in whatever order the source listed them |
| Empty Entry Rows | `Long[]` | May be empty | ✅ | Spreadsheet rows that were completely blank, numbered as in the person's own file (header is row 1). Always empty for an image |
| Unidentified Entry Rows | `Long[]` | May be empty | ✅ | Spreadsheet rows with content but no readable name, numbered as in the person's own file (header is row 1). Always empty for an image |
| Unidentified Entries Notes | `String` | Concise, plain text | ❌ | Anything else worth flagging, including when no relevant customer data could be detected. For an image, describes missed entries. For a spreadsheet read in groups, one combined message |

**ExtractCustomerIndividualData**

A `candidate` part with the same fields and constraints as **CustomerIndividual** ([step 1](#1-user-adds-a-customer)) — Full Name, Emails, Phone Numbers, and Addresses — where each phone number has only a national number and a country dialling code, and each address carries its type. Alongside it:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Is Duplicate | `Boolean` | — | ✅ | True when another candidate of the same kind in this same response has a matching name, ignoring capitalisation |
| Extraction Notes | `String` | Concise, plain text | ❌ | One short line, only when something was missing or unclear, or the name was worked out from an email |

**ExtractCustomerBusinessData**

A `candidate` part with the same fields and constraints as **CustomerBusiness** ([step 1](#1-user-adds-a-customer)) — Business Name, Emails, Phone Numbers, Tax ID, Addresses, and Customer Business Contacts — where each phone number has only a national number and a country dialling code, and each address carries its type. Alongside it:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Is Duplicate | `Boolean` | — | ✅ | True when another candidate of the same kind in this same response has a matching name, ignoring capitalisation |
| Extraction Notes | `String` | Concise, plain text | ❌ | One short line, only when something about the business or one of its contacts was missing or unclear, or a name was worked out from an email |

**Outcome**

- Nothing is stored: no customer, no contact, and no copy of the image or file. The response is not remembered either.
- The image or file is read by an outside AI service and not kept afterwards.
- A missing, malformed or mismatched file name stops the request before the file is sent to the AI.
- Using a candidate means adding it through [step 1](#1-user-adds-a-customer), like anything typed in by hand.
- The two counts show when entries were missed — say four found but three processed — and for a spreadsheet the row lists point straight to them.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization id header is missing - The required file name header is missing or malformed - The filename extension is missing, unsupported, or does not match the actual file, including a file that is not a supported image, CSV or Excel file |
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

{% include abbreviations.md %}
