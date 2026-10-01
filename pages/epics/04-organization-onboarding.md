---
title: Organization Onboarding
---

# Organization Onboarding

### Overview

Once someone has finished setting up their own account, they set up the business itself: its name, how to reach it, and its logo. Everything the product does afterwards hangs off the organization created here.

### Related / Out of scope

- **Related** — [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}). A person must finish that first. Verifying a phone number is the last step of personal onboarding and the thing that unlocks this one.
- **Out of scope** — What people do inside an organization once it exists: the customer book, the catalogue, and everything built on top of them.
- **Not built yet** — Joining an organization that already exists. There is no invitation, no request to join, and no way to add a second person to an organization. Today every account that reaches this point creates its own organization and is its only member.

### Requirements across the epic

The organization is set up in two steps, and both need a signed-in person who has finished their own onboarding.

#### Functional

1. Only someone who has completed personal onboarding — meaning their phone number is verified — can set up an organization.
2. Whoever creates an organization becomes its **owner**. Ownership is granted at creation and cannot be handed over.
3. An organization moves through two stages: **details provided** when it is created, then **logo provided** once a logo is uploaded.

#### Non-functional

1. Both steps need a valid access token. A missing, invalid, or expired one is refused with the same error used everywhere else in the product.

### User flow

1. [User Creates an Organization](#1-user-creates-an-organization)
2. [User Uploads a Logo](#2-user-uploads-a-logo)

### Prerequisites

Two ideas are needed to read the steps below.

**Onboard stage** — how far through personal sign up an account is. This epic needs the last one, `PhoneVerified`. The [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}) epic explains the rest.

**Organization role** — what a person may do inside an organization they belong to.

| **Role** | **What it means here** |
| --- | --- |
| Owner | Granted to whoever creates the organization. May do anything, including upload the logo. |
| Admin | May change the organization, including uploading the logo. Nothing grants this role yet. |
| User | An ordinary member. May read, but may not upload a logo. Nothing grants this role yet. |

Only the owner role is ever assigned today, because there is no way to add a second person to an organization.

### 1. User Creates an Organization

**Who can reach this step: a signed-in person whose onboard stage is `PhoneVerified`.** No organization membership is needed — this step is what creates it.

- User arrives here straight after verifying their phone number.
- User fills in the business name, the short name for web addresses, and any contact details they want to record.
- We create the organization, make them its owner, and email them to confirm.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User creates an organization with valid details | - The organization is created at stage `DetailsProvided`, with no logo yet - The creator becomes its `Owner` - A confirmation email is sent - Frontend receives the new organization id |
| 2. User picks a short name someone else already has | - Rejected and nothing is stored - Today this is reported as a server error rather than "that name is taken" — see [gap 1](#1-a-short-name-that-is-already-taken-is-reported-as-a-server-error) |
| 3. User supplies contact emails or phone numbers | - Every entry must be valid, and exactly one per list marked as the default - Empty lists are allowed; the organization simply has no recorded contacts |
| 4. User leaves the optional details blank | - The organization is created with only a name and short name |
| 5. The confirmation email cannot be sent | - The organization is still created and the user is not held up |
| 6. User has not finished personal onboarding | - Rejected. Verifying a phone number is what unlocks this step |
| 7. User supplies one address for both shipping and billing, or a shipping address and a billing address | - Each address is stored with its type - None is marked as a default - An empty list is allowed; the organization simply has no recorded address |
| 8. User supplies an address missing a required part, with a type other than shipping, billing, or both, or any other combination of addresses | - Rejected and nothing is stored |

#### Requirements

1. A business name and a short name are required. Everything else — tagline, addresses, company registration number, tax id, contact details — is optional.
2. The short name may contain only lowercase letters, digits and hyphens, is at most 63 characters, and must be unique across the whole product, because it is used in web addresses.
3. When contact emails or phone numbers are given, every entry must be valid and exactly one of them must be marked as the default.
4. We email the creator to confirm, but never let that email delay or block the creation.
5. Every address given needs a first address line, city, postal code, country and type; the second address line is optional, and addresses have no default.
6. When addresses are given there must be either exactly one, marked as both shipping and billing, or exactly two, one shipping and one billing in either order. Three or more, two of the same type, a single shipping-only or billing-only address, or a both-purposes address alongside another are all rejected.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Name | `String` | 1–255 characters, trimmed | ✅ | The business name as people should see it |
| Slug | `String` | Lowercase letters, digits and hyphens only; max 63 characters; unique product-wide | ✅ | The short name used in web addresses |
| Tagline | `String` | 1–255 characters, trimmed | ❌ | A short line describing the business |
| Emails | `EmailEntry[]` | Empty by default | ❌ | Contact email addresses. See **EmailEntry** below |
| Phone Numbers | `PhoneNumberEntry[]` | Empty by default | ❌ | Contact phone numbers. See **PhoneNumberEntry** below |
| Addresses | `AddressEntry[]` | Empty by default; otherwise one `SHIPPING_AND_BILLING` entry, or one `SHIPPING` and one `BILLING` entry | ❌ | The business's addresses. See **AddressEntry** below |
| Company Registration Number | `String` | 1–255 characters, trimmed | ❌ |  |
| Tax ID | `String` | 1–255 characters, trimmed | ❌ |  |

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
| Address Type | `AddressType` | `SHIPPING`, `BILLING` or `SHIPPING_AND_BILLING` | ✅ | What the address is used for. `SHIPPING_AND_BILLING` means one address serves both |

**Address**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Address Line 1 | `String` | 1–255 characters, trimmed | ✅ | Street address |
| Address Line 2 | `String` | 1–255 characters, trimmed | ❌ | Street address, continued |
| City | `String` | 1–255 characters, trimmed | ✅ |  |
| Postal Code | `String` | 1–255 characters, trimmed | ✅ |  |
| Country | `String` | 1–255 characters, trimmed | ✅ | Free text, as the person types it |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form | ✅ | Identifies the new organization. Sent back in the next step and in every later organization request. |

**Outcome**

- The organization exists at stage `DetailsProvided` with no logo, and the creator is its owner. Nothing is ever left half-created.
- A confirmation email is attempted. If it cannot be sent, we carry on.
- If the short name is already taken, nothing at all is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error - Short name has the wrong shape - A contact list has no default, or more than one - The addresses are not an allowed combination |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - The short name is already taken - Unexpected error |

### 2. User Uploads a Logo

**Who can reach this step: a signed-in person whose onboard stage is `PhoneVerified`, who is an owner or admin of the organization they name.**

- User picks an image file for the business.
- We check it really is a supported image, keep it, and make a smaller standard copy.
- The organization moves to its final stage.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. Owner uploads a valid image | - The file is accepted as a PNG, JPEG or WEBP - The original is kept as sent, plus a normalised copy bounded to 640×640 - The organization moves to stage `LogoProvided` |
| 2. User uploads a file that is not a supported image | - Rejected and nothing is stored - A file whose name is not a PNG, JPEG or WEBP name, or whose contents are not what its name says, is refused as a bad request - A file named as a supported image that cannot actually be opened as one is reported as a server error — see [gap 2](#2-choosing-the-wrong-file-is-reported-as-a-server-error) |
| 3. User uploads a file larger than the limit | - Should be rejected with a clear answer - Today it is not: the request stalls with no answer until the sender's own connection gives up. This is a known engineering fault, tracked for a fix |
| 4. A member with the ordinary user role tries to upload | - Rejected. Only owners and admins may change the logo |
| 5. A signed-in person who does not belong to the organization tries to upload | - Rejected - Today this is reported as a server error — see [gap 3](#3-not-belonging-to-an-organization-is-reported-as-a-server-error) |
| 6. The organization id or file name is missing from the request | - Rejected as an invalid request |
| 7. Owner uploads a second logo later | - Same as scenario 1. The new logo is kept and the organization stays at `LogoProvided` |

#### Requirements

1. Only PNG, JPEG and WEBP images are accepted. The file name must end in a matching extension (`.png`, `.jpg`, `.jpeg` or `.webp`), and a file whose contents do not match its name is refused.
2. A file may be up to 20 MB.
3. Two copies are kept: the file exactly as uploaded, and a normalised copy bounded to 640×640.
4. A successful upload moves the organization to its final stage.
5. Only an owner or an admin of that organization may upload its logo.

#### Request / Response / Outcome

**Request**

The body is the raw image file. Two values travel in the request's headers instead of the body:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form | ✅ | Which organization the logo belongs to |
| File Name | `String` | 1–255 characters, trimmed; supported extension matching the actual format | ✅ | The original name of the file, kept with the logo |
| Image | Binary | PNG, JPEG or WEBP; up to 20 MB | ✅ | The logo image itself |

**Response**

Response is empty. A successful upload answers with nothing but a success status.

**Outcome**

- The organization's logo is now this image, kept as uploaded and as a normalised copy bounded to 640×640, along with its original file name.
- The organization moves to stage `LogoProvided`.
- On any refusal nothing is stored and the organization is unchanged.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `BAD_REQUEST_ERROR` | - The organization id header is missing - The file name header is missing - The file name is not a PNG, JPEG or WEBP name - The file's contents do not match its name |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role in the organization does not allow it |
| 500 | `INTERNAL_SERVER_ERROR` | - The file is named as a supported image but cannot be opened as one - The person does not belong to the organization - Unexpected error |

### Known gaps and open questions

Everything above describes what the product does today. Nothing in this section exists yet; each one needs a product answer before it can be built.

#### 1. A short name that is already taken is reported as a server error

Short names are the organization's web address, so collisions are ordinary and expected, like finding a username is gone. Today a taken name comes back as a server error, telling the person something broke on our side, with no message naming the field and no alternative offered.

**To decide:** whether a taken short name should be a plain validation failure naming the field, whether the app should check availability while the person types, and whether we should suggest a free alternative.

#### 2. Choosing the wrong file is reported as a server error

A file with the wrong name or contents is refused as a bad request, but with no message saying which formats would have worked. A file named as a PNG, JPEG or WEBP that cannot actually be opened as an image — a damaged file, say — is reported as a server error, telling the person the product is broken.

Picking the wrong file is one of the most ordinary mistakes in any upload.

**To decide:** the right answer for an unsupported or unreadable file, and whether the accepted formats and size limit should be stated on the upload screen before anyone picks a file.

#### 3. Not belonging to an organization is reported as a server error

Naming an organization the signed-in person is not a member of comes back as a server error, while naming one where they *are* a member but hold the wrong role is correctly refused as not allowed. Both mean "you may not do this", but only one says so.

**To decide:** whether a non-member should be refused exactly like a member with the wrong role, or told the organization does not exist.

#### 4. The final stage is recorded but never used

An organization moves to its logo-provided stage once a logo is uploaded, but nothing anywhere requires it. Either the logo is genuinely optional and the stage records something nothing depends on, or a logo is meant to unlock something and that rule has not been built.

**To decide:** whether a logo is required to consider an organization set up, and if so, what it unlocks.

#### 5. One person can create unlimited organizations

Nothing limits how many organizations one account may create, and nothing lists the ones an account already belongs to. Since creating an organization is the step immediately after personal onboarding, someone who repeats it simply accumulates organizations they own.

**To decide:** whether an account may hold more than one organization, and if so how they choose between them.

{% include abbreviations.md %}
