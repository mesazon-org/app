---
title: Forgot Password
---

# Forgot Password

### Overview

Someone who has forgotten their password can get back into their account using only their email address, without help from anyone.

### Related / Out of scope

- **Related** — [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}). Setting a password for the *first* time happens there; this epic only replaces one that already exists.
- **Related** — [Sign In]({{ site.baseurl }}{% link epics/03-sign-in.md %}). People arrive here after failing to sign in, and go back there once the new password is set.
- **Not built yet** — Changing a password while signed in and knowing the current one. It does not exist anywhere; today this flow is the only way to change a password, even for someone already signed in.

### Requirements across the epic

A **one-time passcode** (OTP) here is the same kind of short code used elsewhere in the product: six characters, letters and digits, usable once. See the [glossary]({{ site.baseurl }}{% link glossary.md %}).

#### Functional

1. Recovery is three steps in order: ask for a code by email, enter the code to get a reset token, then use that token to set a new password.
2. Only an account that already has a password can recover one.
3. No step requires being signed in: the emailed code, and then the reset token, prove who the person is.
4. The code and the reset token each work once; using one uses it up.

#### Non-functional

1. We never confirm or deny that an email address has an account: an unknown address gets the same answer as a known one, with nothing saved or sent. Today there is one exception — see [gap 1](#1-a-registered-email-can-be-told-apart-from-an-unregistered-one).
2. A code cannot be requested or guessed without limit; steps 1 and 2 set the limits.
3. If the email carrying a code cannot be sent, the request fails, so nobody waits for a code that never went out. The confirmation email after a reset is best-effort and never blocks the reset.

### User flow

1. [User Requests a Reset Code by Email](#1-user-requests-a-reset-code-by-email)
2. [User Verifies the Reset Code](#2-user-verifies-the-reset-code)
3. [User Sets a New Password](#3-user-sets-a-new-password)

### Prerequisites

Every account has an **onboard stage** saying how far through sign up it has got. Recovery only accepts accounts at `PasswordProvided` or later — accounts that actually have a password. [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}) explains the stages.

Two counters sit behind several rules below:

| **Counter** | **What it counts** |
| --- | --- |
| Code requests | Requests made while a code is still fresh. Cleared only when a code is entered correctly. |
| Wrong attempts | Code submissions. Cleared when a new code is created, and when a code is entered correctly. |

### 1. User Requests a Reset Code by Email

**Who can reach this step: anyone — no sign-in required.** The account behind the email must be at `PasswordProvided`, `PhoneVerification`, or `PhoneVerified`.

- User chooses "forgot password" on the sign-in page.
- User enters the email address on the account — the only thing they need to remember.
- We email them a six-character code, and they move on to entering it.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User asks for a code and has none outstanding | - A new code is created and emailed - The wrong-attempt counter is cleared - User moves on to entering the code |
| 2. User asks again after their previous code went stale (expired, or inside the resend cooldown before it expires) | - Same as scenario 1: a new code is created and emailed |
| 3. User asks again while their code is still fresh, having asked only a few times | - The same code id comes back and **no** new email is sent - The code's expiry is extended |
| 4. User asks again while their code is still fresh, having already asked too many times | - The same code id comes back and no email is sent - The expiry is **not** extended, and the answer does not show this |
| 5. User asks for a code for an email with no account | - Nothing is saved or sent - A made-up code id comes back, so the page moves on exactly as for a real account |
| 6. User asks for a code for an account that has not set a password yet | - Rejected: there is no password to recover - See [gap 1](#1-a-registered-email-can-be-told-apart-from-an-unregistered-one) |

#### Requirements

1. Anyone who has set a password can ask for a recovery code with their email address.
2. While a code is still fresh, asking again reuses it and sends no new email.
3. Each of the first 3 repeat requests extends a fresh code's life; from the 4th on, the code keeps its expiry.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854); max 255 characters | ✅ | The email address on the account |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| OTP ID | `UUID` | Canonical 36-character form | ✅ | Identifies the code; sent back with the code in step 2 |
| OTP Expires In Seconds | `Long` | Whole seconds | ✅ | How long the code is meant to last (see [gap 2](#2-the-countdown-we-hand-back-can-be-wrong)) |

**Outcome**

- No code, or a stale one: a new code is emailed and the wrong-attempt counter is cleared.
- Fresh code, few requests so far: the code's expiry is pushed out; nothing is sent.
- Fresh code, too many requests: only the request count changes. The code keeps its original expiry, although the answer implies otherwise — see [gap 2](#2-the-countdown-we-hand-back-can-be-wrong).
- Unregistered email: nothing changes.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 403 | `FORBIDDEN_ERROR` | - The account has not set a password yet |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error, including the code email failing to send |

### 2. User Verifies the Reset Code

**Who can reach this step: anyone holding a code id — no sign-in required.**

- User reads the code in the email and types it in.
- The page sends it back with the code id from step 1.
- We hand back a token that allows one password change.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User enters the correct code | - The code is used up and both counters are cleared - Every other session on the account is signed out - A reset token comes back, and the user moves on to the new-password page |
| 2. User enters a wrong code, still with attempts left | - Rejected, and the attempt is counted - The code still works, so a typo can be corrected |
| 3. User enters a code too many times | - The code is thrown away and rejected, even if it is right - Recovering means asking for a new code in step 1 |
| 4. User enters a code that has expired | - The code is thrown away and rejected |
| 5. User submits a code id we have no record of — an old email, a stale tab, a code already used, or the made-up code id from [step 1, scenario 5](#1-user-requests-a-reset-code-by-email) | - Rejected; there is nothing to throw away |

#### Requirements

1. The correct code proves the person reads that mailbox, and earns one chance to set a new password.
2. A wrong code is counted and rejected, but keeps working, so a typo does not force a restart.
3. A code can be tried 5 times; the 6th attempt throws it away, so it cannot be guessed by trying repeatedly.
4. An expired code is thrown away.
5. Every rejection here — wrong, expired, too many attempts, or an unknown code id — gets exactly the same answer, so the made-up code id from step 1 cannot be told apart from a real one.
6. The correct code signs every other session out straight away, so someone recovering from a break-in locks the intruder out without waiting for step 3.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| OTP ID | `UUID` | Canonical 36-character form | ✅ | The code id handed back in step 1 |
| OTP | `String` | Exactly 6 characters, uppercase letters and digits only | ✅ | The code from the email |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Reset Password Token | `String` | JWT | ✅ | Allows exactly one password change; sent back in step 3 |
| Reset Password Token Expires In Seconds | `Long` | Whole seconds | ✅ | How long that token stays usable |

**Outcome**

- Correct code: the code is used up, both counters are cleared, and every other session is signed out. Those sessions cannot be renewed; a sign-in already in hand keeps working only until it runs out shortly afterwards.
- Wrong code with attempts left: only the attempt count changes.
- Too many attempts, or an expired code: the code is thrown away and nothing is issued.
- Unknown code id: nothing changes.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 400 | `BAD_REQUEST_ERROR` | - The code was wrong - The code has expired - Too many attempts - Code id not recognized |
| 403 | `FORBIDDEN_ERROR` | - The account has not set a password yet |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 3. User Sets a New Password

**Who can reach this step: anyone holding a reset token — no sign-in required.**

- User types a new password.
- The page sends it with the reset token from step 2.
- We change the password and send them back to sign in.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User submits a new password with a valid token | - The password is changed and the token is used up - A confirmation email is sent - User can sign in with the new password |
| 2. User submits a password that breaks the rules | - Rejected with what is wrong - The token still works, so they can try again |
| 3. User submits a token that has already been used, or has expired | - Rejected; recovering means starting again from step 1 - A used token currently gets a server error — see [gap 3](#3-submitting-a-used-reset-token-answers-with-a-server-error) |
| 4. The confirmation email cannot be sent | - The password change still succeeds - The failure is recorded, and the user is not held up |

#### Requirements

1. A valid reset token allows exactly one password change.
2. The new password must meet the same rules as any other password on the account.
3. The account holder is emailed that their password changed, so an unexpected change is noticed.
4. A rejected reset token gets a different answer from the code rejections in step 2, and means starting over from step 1.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Reset Password Token | `String` | JWT | ✅ | The token handed back in step 2 |
| Password | `String` | At least 1 lowercase letter, 1 uppercase letter, 1 digit and 1 special character `@$!%#*^,?)(&._-`; length 8–72; no other characters allowed | ✅ | The new password |

**Response**

Response is empty.

**Outcome**

- The new password replaces the old one, and the reset token stops working.
- A confirmation email is sent to the account's address; if it cannot be sent, the reset still stands.
- Other sessions were already signed out in [step 2](#2-user-verifies-the-reset-code); this step does not repeat it.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error - Password does not meet the rules |
| 401 | `UNAUTHORIZED_ERROR` | - The reset token is invalid or has expired |
| 403 | `FORBIDDEN_ERROR` | - The account has not set a password yet |
| 500 | `INTERNAL_SERVER_ERROR` | - The reset token has already been used - Unexpected error |

### Known gaps and open questions

Everything above describes what the product does today. Nothing in this section exists yet; each one needs a product answer before it can be built.

#### 1. A registered email can be told apart from an unregistered one

Today an unknown email gets a normal answer with a made-up code id, but a registered email that has not set a password yet is rejected. Because the answers differ, anyone can tell which addresses have an account — defeating the made-up code id.

**To decide:** whether the not-yet-onboarded case should answer normally too, exactly like an unknown address.

#### 2. The countdown we hand back can be wrong

Today, once someone has asked too many times, the code's expiry is no longer extended, but the answer still reports a full fresh lifetime. A countdown on the page keeps running after the code has stopped working.

**To decide:** whether the answer should report the code's real remaining time rather than the configured maximum.

#### 3. Submitting a used reset token answers with a server error

Today a reset token that has already been used — a second click, or a refresh after a successful reset — gets `500 INTERNAL_SERVER_ERROR`, which says something broke when nothing did.

**To decide:** the right answer here; it looks like "this is no longer valid, please start again".

#### 4. The request counter never resets except on success

Today the request counter is cleared only when a code is entered correctly, not when a new code is created. The limit becomes "requests ever, until a successful recovery": someone who abandons recovery a few times, even months apart, stops getting extensions with nothing to explain why.

**To decide:** whether this counter should reset whenever a new code is created, as the wrong-attempt counter already does.

#### 5. The code may not live long enough to be usable

Today the code lasts **45 seconds** by default, with the resend cooldown at **15 seconds**. That must cover sending, delivery, noticing, opening the email and typing the code; email delivery alone can take longer, which pushes people to ask again — exactly what [gap 4](#4-the-request-counter-never-resets-except-on-success) then penalises.

**To decide:** what the lifetime of an emailed code should be, given that the phone and email verification codes elsewhere in the product may want different values.

{% include abbreviations.md %}
