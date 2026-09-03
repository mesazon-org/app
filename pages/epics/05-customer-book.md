---
title: Customer Book
---

# Customer Book

### Overview

Every organization keeps a book of the people and companies it trades with. It is the organization's own address book, and it is where future orders will point.

### Related / Out of scope

- **Related** — [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}). An organization must exist first, and every request here names which organization it is for.
- **Out of scope** — Who may belong to an organization and what each role means. That is set up with the organization itself.
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

### User flow

Unlike the earlier epics, these steps are not a single journey. They are the stages of a customer's life in the book, and each stands on its own.

1. [User Adds a Customer](#1-user-adds-a-customer)
2. [User Browses the Customer Book](#2-user-browses-the-customer-book)
3. [User Opens a Customer](#3-user-opens-a-customer)
4. [User Updates a Customer](#4-user-updates-a-customer)
5. [User Manages a Business's Contacts](#5-user-manages-a-businesss-contacts)
6. [User Archives a Customer](#6-user-archives-a-customer)

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

Response is empty. A successful add answers with nothing but a success status — including no identifier for what was just created. See [gap 1](#1-adding-a-customer-tells-you-nothing-about-what-was-added).

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
| 401 | `UNAUTHORIZED_ERROR` | - Session is missing or invalid |
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
| 2. The organization has archived customers | - Archived customers do not appear - There is no way to list them again - See [gap 4](#4-archiving-is-final-and-archived-customers-cannot-be-found-again) |
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
| 401 | `UNAUTHORIZED_ERROR` | - Session is missing or invalid |
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
| 3. User opens a customer that does not exist, or asks for a person using the business screen | - Reported as a server error rather than "not found" — see [gap 2](#2-looking-up-a-customer-that-is-not-there-is-reported-as-a-server-error) |

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

Neither answer includes the business's contacts, and neither says whether the customer is archived. See [gap 3](#3-changes-to-an-archived-customer-are-silently-discarded).

**Outcome**

Nothing changes. This step only reads.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - Session is missing or invalid |
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
| 3. User changes a customer that has been archived | - Nothing happens, and the change is reported as successful - See [gap 3](#3-changes-to-an-archived-customer-are-silently-discarded) |
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

Response is empty. A successful change answers with nothing but a success status — and so does a change that quietly did nothing, which is what makes [gap 3](#3-changes-to-an-archived-customer-are-silently-discarded) hard to notice.

**Outcome**

- For an active customer the details are replaced as described and nothing else changes.
- For an archived or missing customer nothing is stored, and the answer is the same as success.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - One or more fields are invalid |
| 400 | `BAD_REQUEST_ERROR` | - The organization was not named on the request |
| 401 | `UNAUTHORIZED_ERROR` | - Session is missing or invalid |
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
| 5. User adds or removes contacts on an archived or missing business | - Nothing happens, and it is reported as successful - See [gap 3](#3-changes-to-an-archived-customer-are-silently-discarded) |

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
| 401 | `UNAUTHORIZED_ERROR` | - Session is missing or invalid |
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
| 3. User wants an archived customer back | - Not possible. There is no way to reverse archiving - See [gap 4](#4-archiving-is-final-and-archived-customers-cannot-be-found-again) |

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
| 401 | `UNAUTHORIZED_ERROR` | - Session is missing or invalid |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role does not allow changes |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### Known gaps and open questions

Everything above describes what the product does today. Nothing in this section exists yet; each one needs a product answer before it can be built.

#### 1. Adding a customer tells you nothing about what was added

Adding a customer — one, a batch, or a business with its contacts — answers with success and nothing else. No identifier comes back.

So the app cannot open what the person just created, cannot link to it, and cannot show it in place. The only way to find a new customer is to fetch the whole book again and look for the name, which is also the only way to discover the identifier of a contact that was just added.

**To decide:** whether adding should return the new customer's identifier — and for a batch, the identifiers in the order they were sent.

#### 2. Looking up a customer that is not there is reported as a server error

Opening a customer that does not exist, or asking for a person through the business screen, comes back as a server error rather than "not found".

Both are ordinary: a stale link, a bookmark to something since archived, or simply the wrong screen for that kind of customer. The person is told the product is broken.

This is the same shape as the missing-record errors in [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}#4-ordinary-situations-answer-with-a-server-error) and [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#3-not-belonging-to-an-organization-is-reported-as-a-server-error), and the three should be answered together.

**To decide:** whether a missing or wrong-kind customer should be a plain "not found".

#### 3. Changes to an archived customer are silently discarded

Changing an archived customer, or adding and removing its contacts, does nothing at all — and reports success. The same happens for a customer that does not exist.

Nothing distinguishes "saved" from "quietly ignored". Someone editing a customer archived by a colleague moments earlier sees their change accepted, then finds it gone. Because an archived customer can still be opened directly, this is easy to reach: the screen loads and looks perfectly editable.

The reasoning is that archiving already achieves what the edit wanted. That holds for archiving something twice; it does not hold for someone typing a new phone number into a form.

**To decide:** whether editing an archived customer should say so, and whether the screen should show that a customer is archived at all — today nothing in the answer reveals it.

#### 4. Archiving is final, and archived customers cannot be found again

Archiving cannot be undone, and the customer book lists only active customers. There is no way to browse or search archived ones.

Together that means an accidental archive is unrecoverable in practice. The record still exists and can still be opened, but only by someone who kept the identifier — and nothing in the product shows it any more.

**To decide:** whether archiving can be reversed, and whether archived customers should be listable. If reversing is allowed, restoring a customer whose name has since been taken by a new active one needs an answer.

#### 5. Nothing prevents a contact being attached to a person

Contacts belong to businesses. That is a rule the service applies, not one the stored data enforces — the link only requires the contact and the customer to be in the same organization, not that the customer is a business.

Nothing today creates such a record. But nothing would stop a future change, or a direct data fix, from leaving a person carrying contacts that no screen would ever show.

**To decide:** whether the rule should be enforced where the data is kept, rather than only in the code path that happens to write it.

{% include abbreviations.md %}
