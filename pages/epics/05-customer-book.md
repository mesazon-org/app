---
title: Customer Book
---

# Customer Book

### Overview

Every organization keeps a book of the people and companies it trades with. It is the organization's own address book, and it is where future orders will point.

### Related / Out of scope

- **Related** — [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}). An organization must exist first, and every request here names which organization it is for.
- **Out of scope** — Who may belong to an organization and what each role means. That is set up with the organization itself.
- **Out of scope** — Checking a photo's candidates against customers already stored in the book. The only duplicate check made when reading a photo is between candidates found within that same photo.
- **Not built yet** — Orders. The customer book exists so that orders can point at a customer later, but nothing places or records an order yet.
- **Not built yet** — Search, filtering and paging. The list returns every active customer in one go, sorted by name.

### Requirements across the epic

Every request in this epic names the organization it applies to, and only touches that organization's book. Nothing here can see another organization's customers.

#### Functional

1. A customer is either a **person** or a **business**, chosen when they are added and fixed for good. There is no converting one into the other.
2. Only businesses have **contacts** — the individual people you deal with inside that company. A contact is never itself a customer and can never be the target of an order.
3. A customer's name must be unique among that organization's active customers **of the same kind**. A person and a business may share a name, and archiving a customer frees their name for reuse.
4. Customers are **archived**, never deleted. Contacts are the opposite: removing one deletes it outright.
5. A customer may have any number of email addresses and phone numbers. Each business contact has at most one of each.

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
7. [User Extracts Customers from a Photo](#7-user-extracts-customers-from-a-photo)

### Prerequisites

**Which organization** — every request names the organization whose book it is touching, and the person must belong to it.

**Roles** — what a member may do here:

| **Role** | **May read** | **May add, change, archive** |
| --- | --- | --- |
| Owner | Yes | Yes |
| Admin | Yes | Yes |
| User | Yes | No |

**Customer status** — a customer is `Active` or `Archived`. New customers start active. Archiving is one-way.

**Default contact details** — email and phone lists may be left empty. When a list has entries, exactly one of them must be marked as the default.

### 1. User Adds a Customer

**Who can reach this step: an owner or admin of the organization.**

- User chooses whether they are adding a person or a business.
- User fills in the name and whatever contact details and address they have.
- For a business they may also record the people they deal with there.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User adds a person | - Stored as a person, active, with no tax id - Name must not match another active person in this organization |
| 2. User adds a business | - Stored as a business, active - May carry a tax id, which a person may not - Any contacts given are stored in the same operation, so either all of it is saved or none of it |
| 3. User adds several customers at once | - All are stored together or none are - A single failure anywhere rolls the whole batch back |
| 4. User adds a customer whose name is already taken by an active customer of the same kind | - Rejected as a conflict, naming what clashed - A person and a business may share a name; only same-kind clashes are refused |
| 5. User gives two contacts at the same business the same email or phone number | - Rejected as a conflict - Contacts may leave email and phone blank, and any number of contacts may have neither |
| 6. User submits several bad entries at once | - Every problem is reported together - Each is tied to the entry that caused it, including which contact inside which business |
| 7. User leaves the contact lists empty | - Accepted. The customer simply has no recorded contact details |
| 8. A member with the ordinary user role tries to add a customer | - Rejected. Reading is open to everyone; changing is not |

#### Requirements

1. A name is required. Everything else — contact details, address, tax id, contacts — is optional.
2. A tax id may be recorded for a business and never for a person.
3. When email or phone lists are given, every entry must be valid and exactly one must be marked as the default.
4. A name must be unique among active customers of the same kind within the organization.
5. Within one business, no two contacts may share an email address, and no two may share a phone number.
6. Adding several customers at once succeeds completely or not at all.

#### Request / Response / Outcome

A customer can be added one at a time, several of one kind at once, or as a mixed batch of both kinds. All five ways carry the same two shapes — the batch forms simply wrap them in lists:

| **What the user is adding** | **What is sent** |
| --- | --- |
| One person | One `CustomerIndividual` |
| One business | One `CustomerBusiness` |
| Several people at once | `CustomerIndividual[]` |
| Several businesses at once | `CustomerBusiness[]` |
| A mixed batch | `CustomerIndividual[]` and `CustomerBusiness[]` together |

The two shapes are separate and neither is a variant of the other. A person has a name and no tax id and no contacts; a business has a business name and may have both.

**Request — CustomerIndividual**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed | ✅ | The person's name |
| Emails | `EmailEntry[]` | Empty by default | ❌ | Contact addresses. See **EmailEntry** below |
| Phone Numbers | `PhoneNumberEntry[]` | Empty by default | ❌ | Contact numbers. See **PhoneNumberEntry** below |
| Address Line 1, Address Line 2, City, Postal Code, Country | `String` | 1–255 characters, trimmed | ❌ | Where they are |

**Request — CustomerBusiness**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Business Name | `String` | 1–255 characters, trimmed | ✅ | The company's name |
| Emails | `EmailEntry[]` | Empty by default | ❌ | Contact addresses. See **EmailEntry** below |
| Phone Numbers | `PhoneNumberEntry[]` | Empty by default | ❌ | Contact numbers. See **PhoneNumberEntry** below |
| Tax ID | `String` | 1–255 characters, trimmed | ❌ | The company's tax reference. A person may never have one |
| Customer Business Contacts | `BusinessContact[]` | Empty by default | ❌ | People inside the business. See **BusinessContact** below |
| Address Line 1, Address Line 2, City, Postal Code, Country | `String` | 1–255 characters, trimmed | ❌ | Where they are |

The shapes used above and throughout this epic:

**EmailEntry**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854); max 255 characters | ✅ | One contact address |
| Is Default | `Boolean` | Exactly one entry in the list must be true | ✅ | Marks the address to use by default |

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

**BusinessContact**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed | ✅ | The contact's name |
| Role | `String` | 1–255 characters, trimmed | ❌ | What they do there |
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854); max 255 characters; unique within the business | ❌ |  |
| Phone Number | `PhoneNumber` | Unique within the business | ❌ |  |

**Response**

A successful add answers with the row(s) just created. Adding one person or one business returns that customer's full stored details — for a business, including the generated identifier of every inline contact. Adding a batch of one kind, or a mixed batch of both, returns a summary per customer instead, in the order it was sent — individuals first, then businesses, for a mixed batch.

**Response — one CustomerIndividual**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | The new customer's identifier |
| Full Name | `String` | — | ✅ | The person's name |
| Emails | `EmailEntry[]` | May be empty | ✅ | Every recorded address, each marked default or not |
| Phone Numbers | `PhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Address Line 1, Address Line 2, City, Postal Code, Country | `String` | — | ❌ | Present only if recorded |

**Response — one CustomerBusiness**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | The new customer's identifier |
| Business Name | `String` | — | ✅ | The company's name |
| Emails | `EmailEntry[]` | May be empty | ✅ | Every recorded address, each marked default or not |
| Tax ID | `String` | — | ❌ | Present only if recorded |
| Phone Numbers | `PhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Address Line 1, Address Line 2, City, Postal Code, Country | `String` | — | ❌ | Present only if recorded |
| Customer Business Contacts | `BusinessContactCreated[]` | May be empty | ✅ | Every contact just stored, each carrying its new identifier |

**BusinessContactCreated**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contact ID | `UUID` | Canonical 36-character form | ✅ | The new contact's identifier |
| Full Name | `String` | — | ✅ | The contact's name |
| Role | `String` | — | ❌ | Present only if recorded |
| Email | `String` | — | ❌ | Present only if recorded |
| Phone Number | `PhoneNumber` | — | ❌ | Present only if recorded |

**Response — a batch of one kind, or a mixed batch**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customers | `CustomerSummary[]` | Same order as sent | ✅ | One summary per customer just added. See **CustomerSummary** in [step 2](#2-user-browses-the-customer-book) |

**Outcome**

- The customer is stored as active, fixed as either a person or a business.
- For a business, any contacts given are stored in the same operation as the business itself.
- For a batch, every customer in it is stored together, or none is.
- On any refusal nothing at all is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid, reported together with the entry each belongs to |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - A customer of this kind already has that name - A contact at this business already has that email address - A contact at this business already has that phone number |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 2. User Browses the Customer Book

**Who can reach this step: any member of the organization.**

- User opens the customer book and sees everyone currently in it.
- The list is sorted by name, ignoring capitalisation.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens the customer book | - Every active customer in the organization is returned - Each entry says whether it is a person or a business - Sorted by name, ignoring capitalisation |
| 2. The organization has archived customers | - Archived customers do not appear - There is no way to list them again - See [gap 3](#3-archiving-is-final-and-archived-customers-cannot-be-found-again) |
| 3. The organization has no customers yet | - An empty list is returned |

#### Requirements

1. Any member may read the customer book, whatever their role.
2. The list contains only active customers.
3. Each entry carries enough to show a row and open it: the identifier, the name, and whether it is a person or a business.
4. The list is sorted by name, ignoring capitalisation.

#### Request / Response / Outcome

**Request**

Request is empty apart from naming the organization. There is nothing to search, filter or page by.

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customers | `CustomerSummary[]` | Sorted by name, ignoring capitalisation | ✅ | The book. See **CustomerSummary** below |

**CustomerSummary**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Identifies the customer, used to open them |
| Name | `String` | — | ✅ | The person's or business's name |
| Customer Type | `CustomerType` | `INDIVIDUAL` or `BUSINESS` | ✅ | Which kind, so the right screen can be opened |

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person does not belong to the organization |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 3. User Opens a Customer

**Who can reach this step: any member of the organization.**

- User picks a customer from the book.
- The app asks for the person or the business by identifier, depending on which the list said it was.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens a customer that exists | - Their full details are returned, including all contact details and the address |
| 2. User opens a customer that has been archived | - Their details are still returned. Archiving hides a customer from the list, not from a direct look-up |
| 3. User opens a customer that does not exist, or asks for a person using the business screen | - Reported as a server error rather than "not found" — see [gap 1](#1-looking-up-a-customer-that-is-not-there-is-reported-as-a-server-error) |

#### Requirements

1. A customer is looked up by identifier, and the caller must say which kind they expect.
2. Asking for the wrong kind finds nothing, even when a customer with that identifier exists.
3. An archived customer can still be opened directly.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Which customer to open |

There are two answers, one per kind, and the caller gets whichever they asked for.

**Response — CustomerIndividual**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Identifies the customer |
| Full Name | `String` | — | ✅ | The person's name |
| Emails | `EmailEntry[]` | May be empty | ✅ | Every recorded address, each marked default or not |
| Phone Numbers | `PhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Address Line 1, Address Line 2, City, Postal Code, Country | `String` | — | ❌ | Present only if recorded |

**Response — CustomerBusiness**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Identifies the customer |
| Business Name | `String` | — | ✅ | The company's name |
| Emails | `EmailEntry[]` | May be empty | ✅ | Every recorded address, each marked default or not |
| Tax ID | `String` | — | ❌ | Present only if recorded |
| Phone Numbers | `PhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Address Line 1, Address Line 2, City, Postal Code, Country | `String` | — | ❌ | Present only if recorded |

Neither answer includes the business's contacts, and neither says whether the customer is archived. See [gap 2](#2-changes-to-an-archived-customer-are-silently-discarded).

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person does not belong to the organization |
| 500 | `INTERNAL_SERVER_ERROR` | - No customer of that kind with that identifier - Unexpected error |

### 4. User Updates a Customer

**Who can reach this step: an owner or admin of the organization.**

- User opens a customer and changes their details.
- The kind of customer can never be changed — only their details.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User changes an active customer's details | - The change is saved - Email and phone lists are replaced wholesale by whatever is sent - Fields left out are left as they were |
| 2. User renames a customer to a name another active customer of the same kind already has | - Rejected as a conflict |
| 3. User changes a customer that has been archived | - Nothing happens, and the change is reported as successful - See [gap 2](#2-changes-to-an-archived-customer-are-silently-discarded) |
| 4. User changes a customer that does not exist | - Nothing happens, and it is reported as successful |
| 5. A member with the ordinary user role tries to make a change | - Rejected |

#### Requirements

1. Only active customers can be changed. The kind is fixed at creation and never changes.
2. Email and phone lists are replaced entirely by what is sent, rather than merged. Sending an empty list clears them.
3. Optional single fields left out of the request are left unchanged.
4. A rename must still leave the name unique among active customers of the same kind.

#### Request / Response / Outcome

There are two ways to update, one per kind, and the caller must use the one matching the customer. Updating a person through the business form finds nothing, and changes nothing.

**Request — updating a CustomerIndividual**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Which customer to change |
| Full Name | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep the current name |
| Emails | `EmailEntry[]` | Replaces the whole list | ❌ | Send the complete set, not just additions |
| Phone Numbers | `PhoneNumberEntry[]` | Replaces the whole list | ❌ | Send the complete set, not just additions |
| Address Line 1, Address Line 2, City, Postal Code, Country | `String` | 1–255 characters, trimmed | ❌ | Left out means unchanged |

**Request — updating a CustomerBusiness**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Which customer to change |
| Business Name | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep the current name |
| Emails | `EmailEntry[]` | Replaces the whole list | ❌ | Send the complete set, not just additions |
| Tax ID | `String` | 1–255 characters, trimmed | ❌ | Left out means unchanged |
| Phone Numbers | `PhoneNumberEntry[]` | Replaces the whole list | ❌ | Send the complete set, not just additions |
| Address Line 1, Address Line 2, City, Postal Code, Country | `String` | 1–255 characters, trimmed | ❌ | Left out means unchanged |

Neither form touches the business's contacts. Those are managed on their own, in [step 5](#5-user-manages-a-businesss-contacts).

**Response**

Response is empty. A successful change answers with nothing but a success status — and so does a change that quietly did nothing, which is what makes [gap 2](#2-changes-to-an-archived-customer-are-silently-discarded) hard to notice.

**Outcome**

- For an active customer the details are replaced as described and nothing else changes.
- For an archived or missing customer nothing is stored, and the answer is the same as success.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - An active customer of this kind already has that name |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 5. User Manages a Business's Contacts

**Who can reach this step: an owner or admin of the organization.**

- User opens a business and adds the people they deal with there, or removes ones who have moved on.
- Only businesses have contacts.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User adds contacts to an active business | - The contacts are appended to the ones already there - Existing contacts are untouched |
| 2. User adds a contact whose email or phone number another contact at that business already has | - Rejected as a conflict |
| 3. User adds a contact with no email and no phone number | - Accepted. Any number of contacts may have neither |
| 4. User removes contacts | - The named contacts are deleted outright - Unlike customers, contacts are not archived |
| 5. User adds or removes contacts on an archived or missing business | - Nothing happens, and it is reported as successful - See [gap 2](#2-changes-to-an-archived-customer-are-silently-discarded) |

#### Requirements

1. Contacts belong to a business and are added to it, never created on their own.
2. Adding appends. It never replaces the contacts already recorded.
3. Within one business, no two contacts may share an email address, and no two may share a phone number. Contacts with neither are always allowed.
4. Removing a contact deletes it. There is no archived state for contacts and no way to get one back.
5. Archiving a business keeps its contacts.

#### Request / Response / Outcome

**Request** — adding contacts

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Which business to add to |
| Customer Business Contacts | `BusinessContact[]` | Empty by default | ❌ | The people to add. Same shape as when adding a business, [described in step 1](#1-user-adds-a-customer) |

**Request** — removing contacts

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Which business to remove from |
| Customer Business Contacts | `ContactReference[]` | Empty by default | ❌ | Which contacts to delete. See **ContactReference** below |

**ContactReference**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer Business Contact ID | `UUID` | Canonical 36-character form | ✅ | Identifies one contact to delete |

**Response**

Response is empty for both adding and removing.

**Outcome**

- Adding stores the new contacts alongside the existing ones.
- Removing deletes the named contacts permanently.
- If the business is archived or missing, nothing is stored or deleted and the answer still reports success.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more contacts are invalid (adding only) |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 409 | `CONFLICT_ERROR` | - A contact at this business already has that email address or phone number (adding only) |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

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
2. An archived customer is kept in full, including their contacts, and can still be opened directly by identifier.
3. Archiving frees the name for reuse by a new active customer of the same kind.
4. Archiving cannot be undone.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Which customer to archive |

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
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 7. User Extracts Customers from a Photo

**Who can reach this step: an owner or admin of the organization.**

- User photographs something they already have on paper — a business card, a printed spreadsheet or grid, or a handwritten note from their own notebook listing customers — instead of typing every entry in by hand.
- The photo is sent to an AI model to read.
- The user gets back a list of candidate people and candidate businesses to look over, how many entries were found versus actually turned into candidates, and, if some were missed, a short note on what to look at again.
- Nothing is stored yet. Whichever candidates the user wants to keep are added afterwards the normal way, in [step 1](#1-user-adds-a-customer).

This step exists to help a business move its existing customer book into this product quickly, with as little retyping as possible.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User photographs a page with several clear entries | - Each is returned as a candidate person or a candidate business, whichever the AI judges it to be - Nothing forces a business card or a grid row into one kind or the other |
| 2. An entry has a name but something else about it is unclear or missing (a smudged phone number, no visible email) | - Still returned as a candidate, with a short, plain note on that candidate saying what was missing or unclear - Any detail that is returned still follows the same field rules as adding a customer |
| 3. An entry has no name the AI could make out at all | - Not returned as a candidate - Reflected only in the counts and in a short summary message describing what could not be read and where in the photo to look |
| 4. Two entries in the same photo are the same kind and share a name, ignoring capitalisation | - Both are returned, and each is marked as a possible duplicate of the other |
| 5. Two entries share a name but are different kinds — one looks like a person, the other a business | - Neither is marked as a duplicate. Only matching kinds count |
| 6. The photo has more entries than could be turned into candidates | - The response says how many entries were identified in total and how many were actually turned into candidates, so the user can tell, for example, that 3 of them could not be processed |
| 7. The photo has nothing recognizable as a customer at all | - An empty result is returned, with both counts at zero - This is not treated as an error |
| 8. The photo is not a supported image, or is larger than the limit | - Rejected, the same as any other image upload in this product |
| 9. The AI service has a temporary connection, reading, timeout, busy or service failure | - The photo is tried again up to two times after the first attempt - If all three attempts fail, the person receives a server error and no candidates |
| 10. The AI service rejects the request or returns a response that cannot be decoded | - Not retried - Reported as a server error |
| 11. A member with the ordinary user role tries to use this step | - Rejected. This step is limited the same way as adding a customer |
| 12. User asks for the same photo to be read again | - Reading a photo never stores anything, so this can be repeated freely with no effect on the customer book |

#### Requirements

1. Every candidate is shaped exactly like adding a person or a business in [step 1](#1-user-adds-a-customer) — the same fields, the same two kinds. The AI decides which kind each entry looks like; nothing here fixes a rule for what a business card or a grid row must become.
2. A candidate's name — and a business contact's name, when a candidate business includes one — must not be empty. Every other value that is returned must satisfy its own field rule: names and other text are trimmed and non-empty when present, email values follow the email field's format and length rule, and each phone component follows its own format and length rule. The phone shown in a candidate has exactly two parts — its national number and its country dialling code — and does not expose a regional label or an international-format number. The photo reader is asked for a best-effort real-looking pair, for example national number `5551234567` with country code `+1`, but this step does not prove that the pair is a real number for that country; that check happens when the candidate is actually added. An empty email or phone list is allowed. The instructions given to the photo reader require exactly one entry to be marked as the default whenever either list is non-empty. If the AI returns a value that fails one of the field-level rules, the response cannot be used and the photo read reports a server error rather than returning a partly invalid candidate; the default-count rule is not independently checked at this stage.
3. An entry the photo seemed to contain but that could not be given any name at all is never returned as a candidate.
4. The response always states how many entries were identified in total and how many were actually turned into candidates, so the person can tell at a glance that, for example, 3 entries could not be processed. When some were missed, a short summary message says what could not be read and where in the photo to look, without listing each one separately.
5. Two candidates of the same kind found in the same photo, whose names match once capitalisation is ignored, are each marked as a possible duplicate of the other. This only ever compares candidates found within that one photo — it never looks at customers already stored in the book.
6. The photo itself is judged the same way as any other image upload in this product: by looking inside the file, accepting only PNG, JPEG and WEBP, capped at 20 MB.
7. Unlike the logo and catalogue item image uploads, the photo is never kept. There is no original copy and no resized copy — nothing about it is written to file storage.
8. Nothing is stored in the customer book by this step, however it turns out. A candidate only becomes a real customer once it is sent through [Adding a customer](#1-user-adds-a-customer).
9. Each AI attempt may take up to one minute. Temporary connection, reading, timeout, busy and service failures are tried again twice, after waits of one second and two seconds. Request rejections, image-reading failures and responses that cannot be decoded are not retried. If all three attempts fail, the photo read reports a server error and returns no candidates. A retry may send the photo to the outside AI service more than once and may therefore create more than one charge, even when an earlier attempt generated an answer but its response could not be received.

#### Request / Response / Outcome

**Request**

The body is the photo itself. The organization it is for travels in the request's header, since the body carries the image:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form | ✅ | Which organization's book this photo is for |
| Image | Binary | PNG, JPEG or WEBP; up to 20 MB | ✅ | The photo to read, sent as the request body |

**Response — `ExtractCustomersResponse`**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Entries Identified | `Long` | Whole number, zero or more | ✅ | How many entries the photo seemed to contain in total, including ones that could not be turned into a candidate |
| Entries Processed | `Long` | Whole number, zero or more | ✅ | How many of those were actually turned into a candidate person or business. This, like everything else in the response, is the AI's own reported figure and is not independently checked |
| Customer Individual Candidates | `ExtractCustomerIndividualData[]` | May be empty | ✅ | Recognized people, in whatever order the photo listed them |
| Customer Business Candidates | `ExtractCustomerBusinessData[]` | May be empty | ✅ | Recognized businesses, in whatever order the photo listed them |
| Unidentified Entries Summary | `String` | Concise, plain text | ❌ | Present only when Entries Processed is less than Entries Identified. A short message pointing at what could not be turned into a candidate — for example, "could not read the last 3 entries" or "could not process entries 2, 5 and 6" |

**ExtractCustomerIndividualData**

The response keeps a `candidate` part alongside the extraction metadata. That `candidate` part has the same fields as **CustomerIndividual** ([step 1](#1-user-adds-a-customer)) — Full Name, Emails, Phone Numbers, and address — and every returned field follows the same constraint as an added customer. Phone numbers contain only a national number and a country dialling code. The candidate wrapper also carries:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Is Duplicate | `Boolean` | — | ✅ | True when another candidate of the same kind in this same response has a matching name, ignoring capitalisation |
| Extraction Notes | `String` | Concise, plain text | ❌ | Present only when something about this candidate was missing or unclear, in one short line |

**ExtractCustomerBusinessData**

The response keeps a `candidate` part alongside the extraction metadata. That `candidate` part has the same fields as **CustomerBusiness** ([step 1](#1-user-adds-a-customer)) — Business Name, Emails, Phone Numbers, Tax ID, address, and Customer Business Contacts — and every returned field follows the same constraint as an added customer. Phone numbers contain only a national number and a country dialling code. The candidate wrapper also carries:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Is Duplicate | `Boolean` | — | ✅ | True when another candidate of the same kind in this same response has a matching name, ignoring capitalisation |
| Extraction Notes | `String` | Concise, plain text | ❌ | Present only when something about this candidate, including one of its contacts, was missing or unclear, in one short line |

**Outcome**

- Nothing is stored: no customer, no contact, and no copy of the photo, anywhere.
- The photo is sent to an outside AI service so it can be read, and is not kept afterwards, by us or in file storage.
- Nothing in the response is remembered anywhere once it is sent. Using a candidate means sending it through [Adding a customer](#1-user-adds-a-customer), the same as anything typed in by hand.
- Entries Identified and Entries Processed together are how a reader sees that some were missed — for example, four entries identified but only three processed — and, when that happens, the summary message says what could not be read and where to look.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization id header is missing |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow this |
| 500 | `INTERNAL_SERVER_ERROR` | - The file is not a supported image - The AI service could not be reached, or sent back something that could not be used - Unexpected error |

### Known gaps and open questions

Everything above describes what the product does today. Nothing in this section exists yet; each one needs a product answer before it can be built.

#### 1. Looking up a customer that is not there is reported as a server error

Opening a customer that does not exist, or asking for a person through the business screen, comes back as a server error rather than "not found".

Both are ordinary: a stale link, a bookmark to something since archived, or simply the wrong screen for that kind of customer. The person is told the product is broken.

This is the same shape as the missing-record errors in [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}#4-ordinary-situations-answer-with-a-server-error) and [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#3-not-belonging-to-an-organization-is-reported-as-a-server-error), and the three should be answered together.

**To decide:** whether a missing or wrong-kind customer should be a plain "not found".

#### 2. Changes to an archived customer are silently discarded

Changing an archived customer, or adding and removing its contacts, does nothing at all — and reports success. The same happens for a customer that does not exist.

Nothing distinguishes "saved" from "quietly ignored". Someone editing a customer archived by a colleague moments earlier sees their change accepted, then finds it gone. Because an archived customer can still be opened directly, this is easy to reach: the screen loads and looks perfectly editable.

The reasoning is that archiving already achieves what the edit wanted. That holds for archiving something twice; it does not hold for someone typing a new phone number into a form.

**To decide:** whether editing an archived customer should say so, and whether the screen should show that a customer is archived at all — today nothing in the answer reveals it.

#### 3. Archiving is final, and archived customers cannot be found again

Archiving cannot be undone, and the customer book lists only active customers. There is no way to browse or search archived ones.

Together that means an accidental archive is unrecoverable in practice. The record still exists and can still be opened, but only by someone who kept the identifier — and nothing in the product shows it any more.

**To decide:** whether archiving can be reversed, and whether archived customers should be listable. If reversing is allowed, restoring a customer whose name has since been taken by a new active one needs an answer.

#### 4. Nothing prevents a contact being attached to a person

Contacts belong to businesses. That is a rule the service applies, not one the stored data enforces — the link only requires the contact and the customer to be in the same organization, not that the customer is a business.

Nothing today creates such a record. But nothing would stop a future change, or a direct data fix, from leaving a person carrying contacts that no screen would ever show.

**To decide:** whether the rule should be enforced where the data is kept, rather than only in the code path that happens to write it.

#### 5. Nothing limits how often a photo can be read

[Extracting customers from a photo](#7-user-extracts-customers-from-a-photo) can be called as often as an owner or admin likes, with no limit per person, per organization, or overall. Each call is sent to an outside AI service, which is not free to run.

Nothing today would stop this being called far more than the migration task it is meant for would ever need.

**To decide:** whether a limit is needed, and if so what it should be.

{% include abbreviations.md %}
