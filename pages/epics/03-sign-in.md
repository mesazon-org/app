---
title: Sign In
---

# Sign In

### Overview

Someone who already has an account gets back into it with their email address and password, and picks up wherever they left off.

### Related / Out of scope

- **Related** — [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}). Sign in works before onboarding is finished, on purpose: it is how someone who stopped halfway comes back.
- **Related** — [Forgot Password]({{ site.baseurl }}{% link epics/02-forgot-password.md %}). Where someone goes when they cannot remember the password.
- **Related** — [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}). The answer lists every organization the account belongs to. That epic explains organizations and roles, and holds [an open question about whether an account may belong to more than one]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#5-one-person-can-create-unlimited-organizations). This epic does not answer it; it only reports the memberships that exist.
- **Out of scope** — Keeping a session alive once it has started. Renewing an expired session is its own journey.
- **Not built yet** — No "remember this device", no list of active sessions, and no way to sign out somewhere else. Signing in starts one session and ends every other.

### Requirements across the epic

#### Functional

1. Anyone who has set a password can sign in, even before onboarding is finished. That is how a half-finished sign up is resumed.
2. Signing in starts exactly one session. Every other session the account had ends at that moment, on every device.
3. The answer says how far through onboarding the account is, so the app knows whether to open the product or send the person back to the step they stopped at.
4. The answer also lists every organization the account belongs to, with the person's role in each, so the app knows which organizations are available without asking again.

#### Non-functional

1. The email address and password only ever travel over an encrypted connection.
2. A refusal never says *why*. A wrong password, an unknown email address and a locked account all look the same, so sign in cannot be used to find out which addresses have accounts.
3. The password is never stored or compared as plain text. Only a scrambled form of it is kept, and the check runs against that.

### User flow

1. [User Signs In](#1-user-signs-in)

### Prerequisites

Every account has an **onboard stage** saying how far through sign up it has got. Sign in accepts `PasswordProvided`, `PhoneVerification` and `PhoneVerified` — that is, from the moment a password exists. [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}) explains the stages.

One counter sits behind this flow:

| **Counter** | **What it counts** |
| --- | --- |
| Sign-in attempts | How many times sign in has been tried for this account. Cleared the moment a password is accepted. |

Each organization in the answer carries the person's **role** there — Owner, Admin or User. [Organization Onboarding]({{ site.baseurl }}{% link epics/04-organization-onboarding.md %}#prerequisites) explains what each role can do.

### 1. User Signs In

**Who can reach this step: anyone — no existing session required.** The account must be at `PasswordProvided`, `PhoneVerification` or `PhoneVerified`.

- User enters their email address and password on the sign-in page.
- We check them, end any session the account already had, and start a new one.
- The app sends them into the product if onboarding is finished, or back to the step they stopped at if not.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User signs in with the right password, onboarding finished | - Signed in with a new session - Every other session ends - Failed-attempt count cleared - User lands in the product |
| 2. User signs in with the right password, onboarding not finished | - Same as scenario 1 - App sends the user back to the onboarding step they stopped at |
| 3. User signs in with the wrong password | - Rejected - The attempt is counted - No session starts and none ends |
| 4. User signs in with an email address that has no account | - Rejected exactly like a wrong password, so the two cannot be told apart |
| 5. User gets the password wrong too many times | - Account is blocked for a set period - The right password is rejected too while the block lasts - See [gap 1](#1-a-locked-account-can-be-kept-locked-indefinitely) |
| 6. User tries to sign in before setting a password | - Rejected, because there is no password to check - See [gap 2](#2-sign-in-reveals-whether-an-email-address-has-an-account) |
| 7. User sends no credentials at all | - Rejected |

#### Requirements

1. Every failed attempt counts against the account. Past the limit the account is blocked for a period, and the correct password is refused too while the block lasts.
2. A successful sign in clears the count, so ordinary mistyping never builds up to a block.
3. Organizations in the answer are ordered with the most recently joined first.

#### Request / Response / Outcome

**Request**

Request body is empty. The credentials travel in the request's authorization header as HTTP Basic authentication, which carries the two values below.

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854); max 255 characters | ✅ | The email address on the account. |
| Password | `String` | As set on the account | ✅ | The account's password. |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Access Token | `String` | JWT | ✅ | Signs the person in for ordinary requests. |
| Access Token Expires In Seconds | `Long` | Whole seconds | ✅ | How long the access token stays usable. |
| Refresh Token | `String` | JWT | ✅ | Used to get a new access token once the current one runs out. |
| Onboard Stage | `OnboardStage` | One of the five stage values | ✅ | How far through onboarding the account is, so the app knows where to send the person next. |
| Organizations | `Organization[]` | Ordered most recently joined first | ✅ | Every organization the person belongs to today. Empty for an account that has not joined or created one yet. |

**Organization**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Organization ID | `UUID` | Canonical 36-character form | ✅ | Identifies the organization. |
| Name | `String` | 1–255 characters, trimmed | ✅ | The business name as people should see it. |
| Slug | `String` | Lowercase letters, digits and hyphens only; max 63 characters | ✅ | The short name used in web addresses. |
| Role | `OrganizationUserRole` | One of `OWNER`, `ADMIN`, `USER` | ✅ | What the signed-in person may do in this organization. |
| Logo | `String` | A link to the stored image | ❌ | The organization's logo. Absent if the organization has not uploaded one yet. |

**Outcome**

- The person is signed in with a fresh session.
- Every other session on the account ends, on every device, and a password reset still in progress stops working.
- The failed-attempt count is cleared.
- On a refusal nothing starts and nothing ends. A wrong password, or any attempt while the account is blocked, adds one to the attempt count; every other refusal changes nothing.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - The email address is not a valid address |
| 401 | `UNAUTHORIZED_ERROR` | - No credentials were sent - The email address has no account - The password is wrong - Too many failed attempts |
| 403 | `FORBIDDEN_ERROR` | - The account has not set a password yet |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### Known gaps and open questions

Everything above describes what the product does today. Nothing in this section exists yet; each one needs a product answer before it can be built.

#### 1. A locked account can be kept locked indefinitely

Every attempt, even one made during a block, restarts the block period, and only a successful sign in clears the count — which the block prevents. Anyone who knows an email address can keep trying slowly and hold the owner out for good, and the owner has no way to clear it.

**To decide:** whether attempts during a block should extend it, whether the count should fade on its own, and whether the owner gets a way out, such as a successful password reset clearing the block.

#### 2. Sign in reveals whether an email address has an account

An account that has not set a password yet is refused differently from a wrong password, and no password is needed to get that answer. So anyone can sort a list of addresses into "has an account here" and "does not".

This is the same leak as [gap 1 in Forgot Password]({{ site.baseurl }}{% link epics/02-forgot-password.md %}#1-a-registered-email-can-be-told-apart-from-an-unregistered-one), through a different door; the two should be answered together.

**To decide:** whether a not-yet-onboarded account should be refused exactly like a wrong password.

#### 3. Blocking counts the account, not whoever is trying

Failed attempts are counted per account only, so a stranger's guesses lock out the owner on their own device. This is what lets anyone who knows an email address trigger gap 1.

**To decide:** whether attempts should also be counted per source, so a familiar device is not punished for a stranger's guessing.

#### 4. Signing in silently signs you out everywhere else

One session per account is deliberate, but nobody is told. Signing in on a phone ends the laptop's session without warning, so the one sign that a password was stolen — being signed out unexpectedly — goes unnoticed.

**To decide:** whether ending other sessions should be announced (a notice on the new device, an email, or both), and whether more than one session should be allowed at all.

#### 5. Sign-in enum wire values have not yet been standardized

Today the API uses uppercase values for onboard stage and organization role. The approved target is UpperCamelCase for both mirrored enums: `EmailVerification`, `EmailVerified`, `PasswordProvided`, `PhoneVerification`, `PhoneVerified`, `Owner`, `Admin` and `User`. These targets are not shipped yet.

{% include abbreviations.md %}
