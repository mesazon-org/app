---
title: User Onboarding
---

# User Onboarding

### Overview

People create their own account with their email address. They prove they own the email, set a password, add their name and phone number, and prove they own the phone. Once that is done they can create an organization.

### Related / Out of scope

- **Related** — Signing in. Someone who stops partway through, for example after setting a password but before verifying their phone, comes back by signing in rather than starting sign up again.
- **Out of scope** — Resetting a forgotten password. That only applies once someone has set a password, and it is covered on its own.
- **Not built yet** — Finishing this flow lets someone create an organization. Joining an organization that already exists is not built, so it is not part of this flow today.

### Requirements across the epic

These hold true for the whole of sign up. Requirements that belong to a single step are listed with that step.

Throughout this epic, a **one-time passcode** (OTP) is a short code we send to someone to prove they own an email address or a phone number. Hover over any acronym to see what it stands for, or see the [glossary]({{ site.baseurl }}{% link glossary.md %}).

#### Functional

1. Everyone moves through the same five steps in the same order. Each step only accepts people at the right onboard stage: sign up answers anyone else with a look-alike reply, and every other step turns them away.
2. A passcode is six characters long and mixes letters and digits. Email and phone passcodes both last 45 seconds, then stop working.
3. Every passcode has its own id. To use a passcode the person sends back both the id and the code they received.
4. Someone who leaves partway through keeps their progress. Once they have set a password they come back by signing in; before that, they sign up again with the same email and carry on.
5. An email address belongs to one account only. We strip surrounding spaces and lower-case it first, so `Sam@Example.com ` and `sam@example.com` are the same person.
6. A rejected passcode never uses the access-token error. A missing, invalid, or expired access token gets the same `UNAUTHORIZED_ERROR` used everywhere else in the product.

#### Non-functional

1. Signing up and verifying an email do not need a sign in. They are protected instead by limits on resending passcodes and by never revealing whether an email is already registered.
2. The resend cooldown is the last 15 seconds before a passcode expires, for email and phone alike. Asking for a passcode again while the current one still has more than that left reuses it and sends nothing. Once it is inside that last stretch, or has expired, a new passcode is made and sent.
3. If a verification email or text cannot be sent, the request fails and the person is told. The welcome email is the exception: if it cannot be sent, the person carries on.
4. After 5 wrong tries in a row on the same passcode, the next attempt is rejected like an expired passcode and the passcode is deleted. The count restarts only when a genuinely new passcode is issued or the passcode is verified correctly; reusing a passcode does not restart it. The person is never told how many tries are left.
5. A passcode id we no longer hold, such as a stale browser tab, a reused link, or a passcode already used, is rejected like an expired passcode.

### User flow

1. [User Sign's Up](#1-user-signs-up)
2. [User Verifies Email](#2-user-verifies-email)
3. [User Provides Password](#3-user-provides-password)
4. [User Provides Details](#4-user-provides-details)
5. [User Verifies Phone Number](#5-user-verifies-phone-number)

### Prerequisites

Every account has an **onboard stage** saying how far through sign up it has got. Each step below lists the stages it accepts.

#### Onboard Stages Example:

| **Field Name** | **Type** | **Values** | **Description** |
| --- | --- | --- | --- |
| onboardStage | `OnboardStage` | `EmailVerification` `EmailVerified` `PasswordProvided` `PhoneVerification` `PhoneVerified` | Users onboard stages |

A new account starts at `EmailVerification`; there is no earlier stage.

### 1. User Sign's Up

**Allowed onboard stages: \[EmailVerification, EmailVerified\]** (a brand-new email has no account yet, so no stage check applies; an email past these stages gets a look-alike reply, not an error)

- The person comes from the sign in page and enters their email.
- They always go on to the verify passcode page.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. Signs up for the first time | - Account created at `EmailVerification` - A new passcode is emailed |
| 2. Signs up again after verifying their email but before setting a password | - Stage goes back to `EmailVerification` - They verify again, with the passcode handled as in scenarios 3 to 5 |
| 3. Signs up again once the earlier passcode has expired or is inside its resend cooldown | - A new passcode is emailed |
| 4. Signs up again while the passcode still has more than its resend cooldown left | - Same passcode kept and no email sent - Its expiry restarts at a full window - A new passcode id is returned |
| 5. Signs up again as in scenario 4, for the sixth time in a row on the same passcode | - The passcode is replaced even though it still works - The new one is emailed and its wrong-try count starts over |
| 6. Signs up with an email that already has a password | - Same reply as for a new email, with a fake passcode id - Nothing sent, nothing saved, stage unchanged |

#### Requirements

1. A first-time email gets a new account at `EmailVerification` and an emailed passcode.
2. Signing up again before a password is set restarts email verification at `EmailVerification`, even if the email was already verified.
3. A passcode can be silently reused this way up to 5 times in a row; the 6th such request replaces it with a new, emailed one. The limit counts requests, not time.
4. Each reuse restarts the passcode's full expiry window and returns a new id for the same code.
5. Once a password is set, signing up again changes nothing and sends nothing but replies exactly as for a new email, so sign up never reveals whether an email is registered.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854); max 255 characters | ✅ | Users email |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| OTP ID | `UUID` | Canonical 36-character form | ✅ | Identifies the passcode we just issued. Sent back together with the passcode to verify the email. |
| OTP Expires In Seconds | `Long` | Whole seconds | ✅ | How long the passcode stays usable. |

This response looks the same whatever the email turns out to be. For an email that already has a password set, the OTP ID it carries is a fake that will not verify against anything.

**Outcome**

- A new email has an account at `EmailVerification` and a passcode in its inbox.
- An email part-way through sign up is back at `EmailVerification`, with its passcode reused silently or replaced and emailed, as above.
- An email that already has a password changes nothing and receives nothing.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 2. User Verifies Email

**Allowed onboard stages: \[EmailVerification\]**

- The person arrives from sign up and enters the passcode from their email.
- On success they are signed in and go on to set a password.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. Enters the correct passcode | - Moves to `EmailVerified` - Earlier sign-ins are cancelled and a fresh session starts |
| 2. Enters a wrong passcode, under the limit | - Plain rejection - The passcode still works |
| 3. Enters an expired passcode | - The passcode is deleted - Plain rejection |
| 4. Tries again after 5 wrong tries in a row | - Rejected without checking the code, and the passcode is deleted - A new one means signing up again (see [step 1](#1-user-signs-up)) |
| 5. Sends a passcode id we hold no record of, including the fake id from [step 1, scenario 6](#1-user-signs-up) | - Plain rejection - Nothing deleted |

#### Requirements

1. The correct passcode marks the email verified and moves the person to `EmailVerified`.
2. Verifying starts a fresh session and cancels any sign-in the person had before.
3. Every failure here gets the same plain rejection, so the fake id from [step 1, scenario 6](#1-user-signs-up) cannot be told apart from a real passcode guessed wrong.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| OTP ID | `UUID` | Canonical 36-character form | ✅ | The passcode id returned by sign up. |
| OTP | `String` | Exactly 6 characters, uppercase letters and digits only | ✅ | The passcode from the email. |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Access Token | `String` | JWT | ✅ | Signs the person in so they can carry on through the remaining steps. |
| Access Token Expires In Seconds | `Long` | Whole seconds | ✅ | How long the access token stays usable. |
| Refresh Token | `String` | JWT | ✅ | Used to get a new access token once the current one runs out. |
| Onboard Stage | `OnboardStage` | `EmailVerified` | ✅ | The stage the person has moved to. |

**Outcome**

- The person is at `EmailVerified` and signed in; the passcode cannot be used again and any earlier sign-in stops working.
- A wrong passcode changes nothing. An expired passcode, or a try after 5 wrong ones, deletes it, and the person must sign up again for a new one.
- An unknown passcode id changes nothing.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 400 | `BAD_REQUEST_ERROR` | - Wrong, expired or unrecognised passcode, or too many wrong tries |
| 403 | `FORBIDDEN_ERROR` | - Invalid onboard stage |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 3. User Provides Password

**Allowed onboard stages: \[EmailVerified\]**

- The person sets a password for their account, then goes on to add their details.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. Sets a password | - Password saved; moves to `PasswordProvided` and can now sign in - A welcome email is attempted and never holds them up |

#### Requirements

1. The password is stored scrambled, never as the person typed it.
2. The person moves to `PasswordProvided` and from then on signs in with the password.
3. We try to send a welcome email; if it fails, the step still succeeds.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Password | `String` | At least 1 lowercase letter, 1 uppercase letter, 1 digit and 1 special character `@$!%#*^,?)(&._-`; length 8–72; no other characters allowed | ✅ | The user password |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Onboard Stage | `OnboardStage` | `PasswordProvided` | ✅ | The stage the person has moved to. |

**Outcome**

- The person has a password, is at `PasswordProvided`, and can sign in.
- They receive a welcome email if it could be sent.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Invalid onboard stage |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 4. User Provides Details

**Allowed onboard stages: \[PasswordProvided, PhoneVerification\]**

- The person gives their full name and phone number, then goes on to verify the phone.
- They can come back here to change their number or get a new passcode.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. Submits their details | - Details saved; moves to `PhoneVerification` - A passcode is texted |
| 2. Submits again while the passcode still has more than its resend cooldown left | - Details saved, including a changed number - Same passcode kept and no text sent |
| 3. Submits again once the passcode is inside its resend cooldown or expired | - Details saved - A new passcode is texted |
| 4. Gives a phone number that belongs to a different account | - Rejected as a conflict - Nothing saved and no text sent |
| 5. Gives the number already saved on their own account | - Not a conflict; behaves as scenario 2 or 3 |

#### Requirements

1. Each submission saves the name and phone number, replacing what was there, and moves the person to `PhoneVerification`.
2. A reused passcode keeps its original expiry; the reply shows the time it has left.
3. A phone number belongs to one account only. A number already saved on a different account is refused and nothing changes — not the name, the number, or the stage.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed | ✅ | The user full name |
| Phone Number | `PhoneNumber` | Must not already belong to a different account | ✅ | The number to send the passcode to. See **PhoneNumber** below |

**PhoneNumber**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Phone National Number | `String` | 1–255 characters, trimmed; must be a real number for its country | ✅ | The number without its country code |
| Phone Country Code | `String` | 1–255 characters, trimmed; must be a real country dialling code | ✅ | The country dialling code |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Onboard Stage | `OnboardStage` | `PhoneVerification` | ✅ | The stage the person has moved to. |
| OTP ID | `UUID` | Canonical 36-character form | ✅ | Identifies the passcode we texted them. Sent back together with the passcode in the next step. |
| OTP Expires In Seconds | `Long` | Whole seconds | ✅ | How long the passcode stays usable. |

**Outcome**

- The person's name and number are saved and they are at `PhoneVerification`.
- They have a passcode waiting: a newly texted one, or the one they already had.
- A conflicting number changes nothing and sends nothing.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Invalid onboard stage |
| 409 | `CONFLICT_ERROR` | - The phone number given already belongs to a different account |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 5. User Verifies Phone Number

**Allowed onboard stages: \[PhoneVerification\]**

- The person arrives from providing details and enters the passcode from the text message.
- On success sign up is finished and they go on to create an organization.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. Enters the correct passcode | - Moves to `PhoneVerified`; sign up is finished |
| 2. Enters a wrong or expired passcode | - Told what went wrong - A wrong one still works; an expired one is deleted |
| 3. Reloads the page while a passcode is waiting | - Can look up the passcode id and time left - Nothing new is made or sent |
| 4. Tries again after 5 wrong tries in a row | - Rejected like an expired passcode, and the passcode is deleted - A new one means going back to [providing details](#4-user-provides-details) |
| 5. Sends a passcode id we hold no record of | - Rejected like an expired passcode; nothing deleted - A new one means going back to [providing details](#4-user-provides-details) |
| 6. Opens the page when no passcode is waiting | - Rejected like an expired passcode - A new one means going back to [providing details](#4-user-provides-details) |

#### Requirements

1. The correct passcode moves the person to `PhoneVerified`; sign up is finished and they can create an organization.
2. A wrong passcode gets its own answer. An expired passcode, too many wrong tries, an unknown id, or no passcode waiting all get the expired answer, never a server error.
3. Someone who reloads this page can look up the passcode they are waiting on without filling in their details again.

#### Request / Response / Outcome

**Request**

Submitting the passcode:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| OTP ID | `UUID` | Canonical 36-character form | ✅ | The passcode id returned by providing details. |
| OTP | `String` | Exactly 6 characters, uppercase letters and digits only | ✅ | The passcode from the text message. |

Looking up a passcode already waiting: request is empty — the person is identified by their session.

**Response**

After submitting the passcode:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Onboard Stage | `OnboardStage` | `PhoneVerified` | ✅ | The stage the person has moved to. Sign up is complete. |

When looking up a passcode already waiting:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| OTP ID | `UUID` | Canonical 36-character form | ✅ | Identifies the passcode still outstanding. |
| OTP Expires In Seconds | `Long` | Whole seconds | ✅ | How long that passcode stays usable. |

**Outcome**

- The person is at `PhoneVerified`, the passcode cannot be used again, and they can create an organization.
- A wrong passcode changes nothing. An expired passcode, or a try after 5 wrong ones, deletes it, and the person goes back to providing details for a new one.
- An unknown passcode id, or no passcode waiting, changes nothing.
- Looking up a passcode normally changes nothing, but deletes it when it is inside its resend cooldown — see [gap 1](#1-opening-the-phone-verification-page-can-destroy-a-usable-passcode).

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 400 | `BAD_REQUEST_ERROR` | - OTP was wrong |
| 401 | `UNAUTHORIZED_OTP_ERROR` | - Expired or unrecognised passcode, too many wrong tries, or no passcode waiting |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Invalid onboard stage |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### Known gaps and open questions

Everything above describes what the product does today. Nothing here exists yet; each needs a product answer before it can be built.

#### 1. Opening the phone verification page can destroy a usable passcode

Today, looking up a waiting phone passcode deletes it and reports it as expired once it is inside its resend cooldown, even though submitting it would still work. A person who reloads the page in that last stretch loses a passcode they could have used.

**To decide:** whether the lookup should ever delete a passcode, or only report how long is left.

{% include abbreviations.md %}
