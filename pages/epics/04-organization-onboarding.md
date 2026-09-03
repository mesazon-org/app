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
4. Every organization has a short name used in web addresses. It must be unique across the whole product.

#### Non-functional

1. Both steps need a valid session. The logo step additionally requires the person to be an owner or an admin of the organization they name.
2. An uploaded image is judged by looking inside the file, never by its extension or by what the upload claims it is. Only PNG, JPEG and WEBP are accepted.
3. An upload is capped at 20 MB. A larger body is read to the end and discarded rather than abandoned part-way, so the sender always gets a clean answer instead of a broken connection.
4. Every logo is kept twice: exactly as uploaded, and as a normalised copy bounded to 640×640. Logos are served straight from file storage, never through the product itself.
5. The email confirming an organization was created is best-effort. If it cannot be sent, the organization still exists and the person is not held up.

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
| 1. User creates an organization with valid details | - The organization is stored at stage `DetailsProvided` with no logo yet - The creator is recorded as its `Owner` - Both are written together, so a failure leaves neither behind - A confirmation email is sent - Frontend receives the new organization id |
| 2. User picks a short name someone else already has | - Rejected, because short names must be unique across the whole product - Nothing is stored - Today this is reported as a server error rather than a "that name is taken" message — see [gap 1](#1-a-short-name-that-is-already-taken-is-reported-as-a-server-error) |
| 3. User supplies contact emails or phone numbers | - Every entry is validated - Exactly one entry in each list must be marked as the default - Empty lists are allowed; the organization simply has no recorded contacts |
| 4. User leaves the optional details blank | - Tagline, address, company registration number and tax id may all be omitted - The organization is created with only a name and short name |
| 5. The confirmation email cannot be sent | - The organization is still created - The failure is recorded for us and the user is not held up |
| 6. User has not finished personal onboarding | - Rejected. Verifying a phone number is what unlocks this step |

#### Requirements

1. A business name and a short name are required. Everything else — tagline, address, company registration number, tax id, contact details — is optional.
2. The short name may contain only lowercase letters, digits and hyphens, is at most 63 characters, and must be unique across the whole product, because it is used in web addresses.
3. When contact emails or phone numbers are given, every entry must be valid and exactly one of them must be marked as the default.
4. The organization and the owner membership are created together. If either fails, neither is stored.
5. Whoever creates the organization becomes its owner.
6. We email the creator to confirm, but never let that email delay or block the creation.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Format** | **Description** |
| --- | --- | --- | --- |
| Name | `String` | Required | The business name as people should see it |
| Slug | `String` | Required - lowercase letters, digits and hyphens only - max 63 characters - must be unique product-wide | The short name used in web addresses |
| Tagline | `String` | Optional | A short line describing the business |
| Emails | `Object[]` | Optional, empty by default | Contact email addresses. Each entry holds the fields below |
| → Email | `String` | A valid email address | One contact address |
| → Is Default | `Boolean` | Exactly one entry must be true | Marks the address to use by default |
| Phone Numbers | `Object[]` | Optional, empty by default | Contact phone numbers. Each entry holds the fields below |
| → Phone Number | `Object` | National number and country code | One contact number |
| → Is Default | `Boolean` | Exactly one entry must be true | Marks the number to use by default |
| Address Line 1 | `String` | Optional | Street address |
| Address Line 2 | `String` | Optional | Street address, continued |
| City | `String` | Optional | |
| Postal Code | `String` | Optional | |
| Country | `String` | Optional | |
| Company Registration Number | `String` | Optional | |
| Tax ID | `String` | Optional | |

**Response**

| **Field Name** | **Type** | **Format** | **Description** |
| --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form | Identifies the new organization. Sent back in the next step and in every later organization request. |

**Outcome**

- The organization is stored at stage `DetailsProvided` with no logo, and a membership row makes the creator its owner. Both happen in one go, so a failure leaves nothing behind.
- A confirmation email is attempted. If it cannot be sent we record that and carry on.
- If the short name is already taken nothing at all is stored.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error - Short name has the wrong shape - A contact list has no default, or more than one |
| 401 | `UNAUTHORIZED_ERROR` | - Session is missing or invalid |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished |
| 500 | `INTERNAL_SERVER_ERROR` | - The short name is already taken - Unexpected error |

### 2. User Uploads a Logo

**Who can reach this step: a signed-in person whose onboard stage is `PhoneVerified`, who is an owner or admin of the organization they name.**

- User picks an image file for the business.
- We check it really is an image, store it, and make a smaller standard copy.
- The organization moves to its final stage.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. Owner uploads a valid image | - The file is confirmed to be a PNG, JPEG or WEBP by inspecting its contents - The original is stored as sent - A normalised copy is stored, bounded to 640×640 - The organization moves to stage `LogoProvided` |
| 2. User uploads a file that is not a supported image | - Rejected and nothing is stored - Today this is reported as a server error rather than "that file type is not supported" — see [gap 2](#2-choosing-the-wrong-file-is-reported-as-a-server-error) |
| 3. User uploads a file larger than the limit | - Rejected - The whole body is still read and discarded, so the sender gets a clean answer rather than a broken connection |
| 4. A member with the ordinary user role tries to upload | - Rejected. Only owners and admins may change the logo |
| 5. A signed-in person who does not belong to the organization tries to upload | - Rejected - Today this is reported as a server error — see [gap 3](#3-not-belonging-to-an-organization-is-reported-as-a-server-error) |
| 6. The organization id or file name is missing from the request | - Rejected as an invalid request |
| 7. Owner uploads a second logo later | - Same as scenario 1. The new files are stored and the organization stays at `LogoProvided` |

#### Requirements

1. Only PNG, JPEG and WEBP files are accepted, and the decision is made by inspecting the file's contents rather than trusting its name or what the upload claims.
2. A file may be up to 20 MB.
3. Two copies are kept: the file exactly as uploaded, and a normalised copy bounded to 640×640.
4. A successful upload moves the organization to its final stage.
5. Only an owner or an admin of that organization may upload its logo.
6. The organization being changed, and the original file name, are named in the request's headers rather than its body, because the body carries the image itself.

#### Request / Response / Outcome

**Request**

The body is the raw image file. Two values travel in the request's headers instead of the body:

| **Field Name** | **Type** | **Format** | **Description** |
| --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form, sent as the `X-Organization-ID` header | Which organization the logo belongs to |
| File Name | `String` | Sent as the `X-File-Name` header | The original name of the file, kept alongside the stored image |
| Image | Binary | PNG, JPEG or WEBP - up to 20 MB | The image itself, sent as the request body |

**Response**

Response is empty. A successful upload answers with nothing but a success status.

**Outcome**

- The file is stored exactly as uploaded, and a normalised copy bounded to 640×640 is stored beside it.
- The organization records both, along with the original file name, and moves to stage `LogoProvided`.
- On any refusal nothing is stored and the organization is unchanged. Working files created while handling the upload are always cleaned up, including when the upload fails.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - The organization id header is missing - The file name header is missing |
| 401 | `UNAUTHORIZED_ERROR` | - Session is missing or invalid |
| 403 | `FORBIDDEN_ERROR` | - Personal onboarding is not finished - The person's role in the organization does not allow it |
| 500 | `INTERNAL_SERVER_ERROR` | - The file is not a supported image - The person does not belong to the organization - Unexpected error |

### Known gaps and open questions

Everything above describes what the product does today. Nothing in this section exists yet; each one needs a product answer before it can be built.

#### 1. A short name that is already taken is reported as a server error

Short names must be unique, and people will collide constantly — it is the organization's web address, so the obvious ones go first. Picking a taken one is an ordinary, expected thing to do, like finding a username is gone.

Today the collision is only caught by the database as the row is written, and comes back as a server error telling the person something broke on our side. There is no message naming the field, and nothing to suggest an alternative.

A check for exactly this exists in the code — a routine that answers whether a short name is already in use — but nothing calls it. It is reachable only from its own test.

**To decide:** whether a taken short name should be a plain validation failure naming the field, whether the app should check availability while the person types, and whether we should suggest a free alternative.

#### 2. Choosing the wrong file is reported as a server error

Uploading a file that is not a PNG, JPEG or WEBP — a document, a screenshot in the wrong format, an image the person's phone saved unusually — is rejected as a server error rather than as "that file type is not supported".

Picking the wrong file is one of the most ordinary mistakes in any upload. The person is told the product is broken, and has no idea which formats would have worked.

**To decide:** the right answer for an unsupported file, and whether the accepted formats and size limit should be stated on the upload screen before anyone picks a file.

#### 3. Not belonging to an organization is reported as a server error

Naming an organization the signed-in person is not a member of comes back as a server error, while naming one where they *are* a member but hold the wrong role is correctly refused as not allowed.

The two are the same kind of answer — you may not do this — but only one says so.

**To decide:** whether a non-member should be refused exactly like a member with the wrong role, or told the organization does not exist.

#### 4. The final stage is recorded but never used

An organization moves to its logo-provided stage once a logo is uploaded, and nothing anywhere requires it. No screen and no rule asks whether an organization has reached it.

So either the logo is genuinely optional, in which case the stage records something nothing depends on, or the product intends to require a logo before something else becomes available, and that rule has not been built.

**To decide:** whether a logo is required to consider an organization set up, and if so, what it unlocks.

#### 5. One person can create unlimited organizations

Nothing limits how many organizations one account may create, and nothing lists the ones an account already belongs to. Since creating an organization is the step immediately after personal onboarding, someone who repeats it simply accumulates organizations they own.

**To decide:** whether an account may hold more than one organization, and if so how they choose between them.

{% include abbreviations.md %}
