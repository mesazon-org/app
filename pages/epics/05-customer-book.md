---
title: Customer Book
---

# Customer Book

### Overview

Every organization keeps a book of the people and companies it trades with. It is the organization's own address book, and it is where future orders will point.

### Related / Out of scope

- **Related** — [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}). An organization must exist first, and every request here names which organization it is for.
- **Out of scope** — Who may belong to an organization and what each role means. That is set up with the organization itself.
- **Out of scope** — Checking an image's or file's candidates against customers already stored in the book. The only duplicate check made when reading an image, CSV file, or Excel file is between candidates found within that same image or file.
- **Not built yet** — Orders. The customer book exists so that orders can point at a customer later, but nothing places or records an order yet.
- **Not built yet** — Search, filtering and paging. The list returns every active customer in one go, sorted by name.

### Requirements across the epic

Every request in this epic names the organization it applies to, and only touches that organization's book. Nothing here can see another organization's customers.

#### Functional

1. A customer is either a **person** or a **business**, chosen when they are added and fixed for good. There is no converting one into the other.
2. Only businesses have **contacts** — the individual people you deal with inside that company. A contact is never itself a customer and can never be the target of an order.
3. A customer's name must be unique among that organization's active customers **of the same kind**. A person and a business may share a name, and archiving a customer frees their name for reuse.
4. Customers are **archived**, never deleted. Contacts are the opposite: removing one deletes it outright.
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
7. [User Extracts Customers from an Image or File](#7-user-extracts-customers-from-an-image-or-file)

### Prerequisites

**Which organization** — every request names the organization whose book it is touching, and the person must belong to it.

**Roles** — what a member may do here:

| **Role** | **May read** | **May add, change, archive** |
| --- | --- | --- |
| Owner | Yes | Yes |
| Admin | Yes | Yes |
| User | Yes | No |

**Customer status** — a customer is `Active` or `Archived`. New customers start active. Archiving is one-way.

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
| 5. User gives two contacts at the same business the same email or phone number | - Rejected as a conflict - Contacts may leave email and phone blank, and any number of contacts may have neither |
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
5. Within one business, no two contacts may share an email address, and no two may share a phone number.
6. Adding several customers at once succeeds completely or not at all.
7. Every address text field is optional. Missing or null values are absent. Supplied values must contain 1–255 characters with no leading or trailing whitespace; empty or whitespace-only values are rejected for that field. An address must contain at least one non-empty text field, its type is required, and together the addresses must be an allowed combination (functional rule 6).

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
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854); max 255 characters; unique within the business | ❌ |  |
| Phone Number | `PhoneNumber` | Unique within the business | ❌ |  |

**Response**

A successful add answers with what was just created. Adding one person or one business returns that customer's full stored details — for a business, including the generated identifier of every inline contact. Adding a batch of one kind, or a mixed batch of both, returns a summary per customer instead, in the order it was sent — individuals first, then businesses, for a mixed batch.

**Response — one CustomerIndividual**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | The new customer's identifier |
| Full Name | `String` | — | ✅ | The person's name |
| Emails | `EmailEntry[]` | May be empty | ✅ | Every recorded email address, each marked default or not |
| Phone Numbers | `PhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Addresses | `AddressEntry[]` | May be empty | ✅ | Every recorded address, each with its type |

**Response — one CustomerBusiness**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | The new customer's identifier |
| Business Name | `String` | — | ✅ | The company's name |
| Emails | `EmailEntry[]` | May be empty | ✅ | Every recorded email address, each marked default or not |
| Tax ID | `String` | — | ❌ | Present only if recorded |
| Phone Numbers | `PhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Addresses | `AddressEntry[]` | May be empty | ✅ | Every recorded address, each with its type |
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
| 409 | `CONFLICT_ERROR` | - A customer of this kind already has that name - A contact at this business already has that email address - A contact at this business already has that phone number |
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

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
- The app asks for the person or the business by identifier, depending on which the list said it was.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User opens a customer that exists | - Their full details are returned, including all contact details and every address with its type |
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
| Emails | `EmailEntry[]` | May be empty | ✅ | Every recorded email address, each marked default or not |
| Phone Numbers | `PhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Addresses | `AddressEntry[]` | May be empty | ✅ | Every recorded address, each with its type |

**Response — CustomerBusiness**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Identifies the customer |
| Business Name | `String` | — | ✅ | The company's name |
| Emails | `EmailEntry[]` | May be empty | ✅ | Every recorded email address, each marked default or not |
| Tax ID | `String` | — | ❌ | Present only if recorded |
| Phone Numbers | `PhoneNumberEntry[]` | May be empty | ✅ | Every recorded number, each marked default or not |
| Addresses | `AddressEntry[]` | May be empty | ✅ | Every recorded address, each with its type |

Neither answer includes the business's contacts, and neither says whether the customer is archived. See [gap 2](#2-changes-to-an-archived-customer-are-silently-discarded).

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - No customer of that kind with that identifier - The person does not belong to the organization - Unexpected error |

### 4. User Updates a Customer

**Who can reach this step: an owner or admin of the organization.**

- User opens a customer and changes their details.
- The kind of customer can never be changed — only their details.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User changes an active customer's details | - The change is saved - Email, phone and address lists are replaced wholesale by whatever is sent - Single fields left out are left as they were |
| 2. User renames a customer to a name another active customer of the same kind already has | - Rejected as a conflict |
| 3. User changes a customer that has been archived | - Nothing happens, and the change is reported as successful - See [gap 2](#2-changes-to-an-archived-customer-are-silently-discarded) |
| 4. User changes a customer that does not exist | - Nothing happens, and it is reported as successful |
| 5. A member with the ordinary user role tries to make a change | - Rejected |
| 6. User replaces an address with some text fields missing, but at least one field has content | - Accepted - The partial address is stored |
| 7. User replaces an address whose text fields are all empty | - Rejected and nothing is stored |

#### Requirements

1. Only active customers can be changed. The kind is fixed at creation and never changes.
2. Email, phone and address lists are replaced entirely by what is sent, rather than merged. Sending an empty list clears them, and leaving a list out is the same as sending it empty.
3. Optional single fields left out of the request, such as the name or tax id, are left unchanged.
4. A rename must still leave the name unique among active customers of the same kind.
5. Address text fields are optional, but every address must contain at least one non-empty text field; an address with all five fields empty is rejected.

#### Request / Response / Outcome

There are two ways to update, one per kind, and the caller must use the one matching the customer. Updating a person through the business form finds nothing, and changes nothing.

**Request — updating a CustomerIndividual**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Which customer to change |
| Full Name | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep the current name |
| Emails | `EmailEntry[]` | Replaces the whole list | ❌ | Send the complete set, not just additions |
| Phone Numbers | `PhoneNumberEntry[]` | Replaces the whole list | ❌ | Send the complete set, not just additions |
| Addresses | `AddressEntry[]` | Replaces the whole list; when not empty, one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Send the complete set, not just additions |

**Request — updating a CustomerBusiness**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Customer ID | `UUID` | Canonical 36-character form | ✅ | Which customer to change |
| Business Name | `String` | 1–255 characters, trimmed | ❌ | Leave out to keep the current name |
| Emails | `EmailEntry[]` | Replaces the whole list | ❌ | Send the complete set, not just additions |
| Tax ID | `String` | 1–255 characters, trimmed | ❌ | Left out means unchanged |
| Phone Numbers | `PhoneNumberEntry[]` | Replaces the whole list | ❌ | Send the complete set, not just additions |
| Addresses | `AddressEntry[]` | Replaces the whole list; when not empty, one `ShippingAndBilling` entry, or one `Shipping` and one `Billing` entry | ❌ | Send the complete set, not just additions |

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
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

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
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

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
| 500 | `INTERNAL_SERVER_ERROR` | - The person does not belong to the organization - Unexpected error |

### 7. User Extracts Customers from an Image or File

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
13. Each AI attempt may take up to one minute, and temporary failures are tried again up to two more times, while rejections and unusable answers are not.
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

#### 1. Looking up a customer that is not there is reported as a server error

Opening a customer that does not exist, or asking for a person through the business screen, comes back as a server error rather than "not found".

Both are ordinary: a stale link, a bookmark to something since archived, or simply the wrong screen for that kind of customer. The person is told the product is broken.

This is the same shape as [Organization Onboarding gap 3]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#3-not-belonging-to-an-organization-is-reported-as-a-server-error), which applies here too: on every step, naming an organization the person does not belong to is a server error. The two should be answered together.

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

Contacts belong to businesses, but only the product's own screens and requests keep it that way; the stored data itself would accept a contact on a person.

Nothing today creates such a record, but a future change or a direct data fix could leave a person carrying contacts no screen would ever show.

**To decide:** whether the rule should be enforced where the data is kept, rather than only in the code path that happens to write it.

#### 5. Nothing limits how often an image or file can be read

[Extracting customers from an image or file](#7-user-extracts-customers-from-an-image-or-file) can be used as often as an owner or admin likes, with no limit per person, per organization, or overall. Each read goes to an outside AI service, which costs money, so nothing stops it being used far more than the migration it is meant for needs.

**To decide:** whether a limit is needed, and if so what it should be.

{% include abbreviations.md %}
