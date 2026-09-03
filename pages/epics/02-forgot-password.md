---
title: Forgot Password
---

# Forgot Password

### Overview

Someone who has forgotten their password should be able to get back into their account using only their email address, without help from anyone.

### Related / Out of scope

- **Related** — [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}). Setting a password for the *first* time happens there. This epic is only about replacing one that already exists.
- **Related** — Signing in. People arrive here from the sign-in page after a failed attempt, and go back there once the new password is set.
- **Out of scope** — Changing a password while signed in and knowing the current one. That is a different journey with different rules, and this flow deliberately does not cover it.
- **Not built yet** — There is no way to change a password from inside the account. Today the only way to change a password is to go through this flow, even for someone already signed in.

### Requirements across the epic

These hold true for the whole of password recovery. Requirements that belong to a single step are listed with that step.

A **one-time passcode** (OTP) here is the same kind of short code used elsewhere in the product: six characters, letters and digits, usable once. See the [glossary]({{ site.baseurl }}{% link glossary.md %}).

#### Functional

1. Recovery is three steps in order: request a reset code by email, verify that code to earn a single-use reset token, then use the token to set a new password. Each step depends on what the previous one handed back.
2. Only someone who has already set a password can recover one. Anyone still earlier in [onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}) has nothing to recover.
3. None of the three steps requires being signed in. Identity is proved by the code we email, and then by the single-use token that entering the code hands back.
4. The code and the reset token are each good for one use. Using one destroys it.

#### Non-functional

1. We never confirm or deny that an email address has an account. Asking to recover an unknown address gets the same answer as a known one, with nothing sent and nothing saved.
2. Asking for a code repeatedly does not send repeated emails. While a code is still fresh we reuse it, and past a small number of requests we stop extending it as well.
3. Entering the wrong code is counted. Past a small number of wrong attempts we stop accepting the code at all, so a code cannot be guessed by trying repeatedly.
4. If the email carrying a code fails to send, we retry a few times and then fail the request, so nobody is left waiting for a code that was never sent. The confirmation email after a successful reset is the exception: it is best-effort and never blocks the reset.
5. A fixed code can be switched on for local development. It is controlled by an environment setting and must stay switched off in production.

### User flow

1. [User Requests a Reset Code by Email](#1-user-requests-a-reset-code-by-email)
2. [User Verifies the Reset Code](#2-user-verifies-the-reset-code)
3. [User Sets a New Password](#3-user-sets-a-new-password)

### Prerequisites

Every account carries an **onboard stage** saying how far through sign up it has got. Password recovery only accepts accounts that have reached `PasswordProvided` or later — that is, accounts that actually have a password. The [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}) epic explains the stages in full.

Two counters run behind this flow and are worth knowing about, because several rules below depend on them:

| **Counter** | **What it counts** |
| --- | --- |
| Code requests | How many times someone has asked for a code while one was already outstanding. Cleared when a code is entered correctly. |
| Wrong attempts | How many times a wrong code has been submitted. Cleared when a new code is generated, and when a code is entered correctly. |

### 1. User Requests a Reset Code by Email

**Who can reach this step: anyone — no sign-in required.** The account behind the email must be at `PasswordProvided`, `PhoneVerification`, or `PhoneVerified`.

- User clicks "forgot password" on the sign-in page, having failed to sign in.
- User enters the email address on the account — the only thing they need to remember.
- We email a six-character code to that address, and the page moves on to asking for it.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User asks for a code and has none outstanding | - A new code is generated with an expiry - The code is emailed to them - The wrong-attempt counter is cleared - Frontend receives the code id - Redirects user to the enter-code page |
| 2. User asks again after their previous code went stale | - Treated exactly as scenario 1: a fresh code is generated and emailed |
| 3. User asks again while their code is still fresh, having asked only a few times | - The existing code is reused - **No** email is sent, because one was already sent - The code's expiry is extended - Frontend receives the same code id |
| 4. User asks again while their code is still fresh, having already asked too many times | - The existing code is reused - No email is sent - The expiry is **not** extended - Frontend receives the same code id and cannot tell this happened |
| 5. User asks for a code for an email with no account | - Nothing is saved and no email is sent - Frontend receives a made-up code id - The page moves on exactly as it would for a real account (this prevents email scanning attacks) |
| 6. User asks for a code for an account that has not set a password yet | - Rejected, because there is no password to recover - See [gap 2](#2-a-registered-email-can-be-told-apart-from-an-unregistered-one) |

#### Requirements

1. Anyone who has set a password can ask for a recovery code using their email address.
2. An email with no account behind it gets the same answer as one that has an account, with nothing saved and nothing sent.
3. While a code is still fresh we reuse it rather than sending another, and asking again extends how long it lasts.
4. Past a small number of requests we stop extending the code and stop sending anything, while still answering normally.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Format** | **Description** |
| --- | --- | --- | --- |
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854) | The email address on the account |

**Response**

| **Field Name** | **Type** | **Format** | **Description** |
| --- | --- | --- | --- |
| OTP ID | `UUID` | Canonical 36-character form | Identifies the code. Sent back together with the code in the next step. |
| OTP Expires In Seconds | `Long` | Seconds | How long the code is meant to last. |

This response looks the same whatever the email turns out to be, so nobody can use it to discover whether an address is registered.

**Outcome**

- With no code outstanding, or a stale one: a new code is saved and emailed, and the wrong-attempt counter is cleared.
- With a fresh code and few requests so far: the code's expiry is pushed out. Nothing is sent.
- With a fresh code and too many requests already: nothing changes at all beyond the request count. The code keeps its original expiry even though the answer says otherwise — see [gap 3](#3-the-countdown-we-hand-back-can-be-wrong).
- For an unregistered email: nothing is saved and nothing is sent.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 403 | `FORBIDDEN_ERROR` | - The account has not set a password yet |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 2. User Verifies the Reset Code

**Who can reach this step: anyone holding a code id — no sign-in required.**

- User opens the email and reads the code.
- User types the code, and the page sends it back along with the code id from step 1.
- We hand back a token that authorises one password change.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User enters the correct code | - The code is checked against the stored one - The code is deleted so it cannot be reused - Both counters are cleared - A single-use reset token is issued and stored - Redirects user to the new-password page |
| 2. User enters a wrong code | - Rejected - The wrong attempt is counted - The code stays usable, so a mistyped code can be corrected |
| 3. User enters a wrong code too many times | - Rejected without the code even being checked - Recovering means going back to step 1 for a new code |
| 4. User enters a code that has expired | - The code is deleted - Rejected, and the user is told the code has expired |

#### Requirements

1. Entering the correct code proves the person reads that mailbox, and earns them one chance to set a new password.
2. A wrong code is counted and rejected, but does not destroy the code — a typo should not force a restart.
3. Past a small number of wrong attempts the code stops being accepted at all, so it cannot be guessed by brute force.
4. An expired code is thrown away rather than left lying around.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Format** | **Description** |
| --- | --- | --- | --- |
| OTP ID | `UUID` | Canonical 36-character form | The code id handed back in step 1 |
| OTP | `String` | - 2-4 Letters - 2-4 Digits - Length 6 | The code from the email |

**Response**

| **Field Name** | **Type** | **Format** | **Description** |
| --- | --- | --- | --- |
| Reset Password Token | `String` | JWT | Authorises exactly one password change. Sent back in step 3. |
| Reset Password Token Expires In Seconds | `Long` | Seconds | How long that token stays usable. |

**Outcome**

- On the correct code: the code is deleted, both counters are cleared, and a reset token is issued and saved so it can be checked and revoked later.
- On a wrong code: only the wrong-attempt count changes. The code survives.
- On an expired code: the code is deleted and nothing is issued.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 400 | `BAD_REQUEST_ERROR` | - The code was wrong - Too many wrong attempts |
| 401 | `UNAUTHORIZED_ERROR` | - The code has expired |
| 403 | `FORBIDDEN_ERROR` | - The account has not set a password yet |
| 500 | `INTERNAL_SERVER_ERROR` | - Code id not found - Unexpected error |

### 3. User Sets a New Password

**Who can reach this step: anyone holding a reset token — no sign-in required.**

- User types a new password.
- The page sends it with the reset token from step 2.
- We change the password and send them back to sign in.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User submits a new password with a valid token | - The new password is scrambled and stored - The reset token is deleted so it works only once - A confirmation email is sent to the account's address - User can now sign in with the new password |
| 2. User submits a password that breaks the rules | - Rejected with what is wrong - The token is not consumed, so they can try again |
| 3. User submits a token that has already been used, or has expired | - Rejected - Recovering means starting again from step 1 |
| 4. The confirmation email cannot be sent | - The password change still succeeds - The failure is recorded for us, and the user is not held up |

#### Requirements

1. A valid reset token allows exactly one password change, and is destroyed by using it.
2. The new password must meet the same rules as any other password on the account.
3. We store the password scrambled, never as the person typed it.
4. We tell the account holder by email that their password changed, so an unexpected change is noticed.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Format** | **Description** |
| --- | --- | --- | --- |
| Reset Password Token | `String` | JWT | The token handed back in step 2 |
| Password | `String` | - at least 1 lowercase letter - at least 1 uppercase letter - at least 1 digit - at least 1 special char \[`@$!%#*^,?)(&._-`\] - length 8-72 - **no other characters allowed** (the password may only contain letters, digits, and the listed special characters) | The new password |

**Response**

Response is empty. A successful reset answers with nothing but a success status.

**Outcome**

- The new password is scrambled and saved over the old one.
- The reset token is deleted, so the same token cannot change the password twice.
- A confirmation email is sent to the account's address. If it cannot be sent, the reset still stands and the failure is only recorded.
- Sessions already signed in elsewhere keep working — see [gap 1](#1-resetting-a-password-does-not-sign-anyone-else-out).

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error - Password does not meet the rules |
| 401 | `UNAUTHORIZED_ERROR` | - The reset token is invalid or has expired |
| 403 | `FORBIDDEN_ERROR` | - The account has not set a password yet |
| 500 | `INTERNAL_SERVER_ERROR` | - The reset token has already been used - Unexpected error |

### Known gaps and open questions

Everything above describes what the product does today. Nothing in this section exists yet; each one needs a product answer before it can be built.

#### 1. Resetting a password does not sign anyone else out

Changing the password leaves every existing session working. Someone who signed in on another device — including someone who should not have — stays signed in afterwards. Those sessions are only cleared the next time the real owner signs in.

This matters because forgetting a password and *suspecting a break-in* look identical from our side, and recovery is exactly what someone reaches for after a break-in. Today it does not lock the intruder out.

**To decide:** whether a reset should end every other session immediately, and whether the person should be told that is what happened.

#### 2. A registered email can be told apart from an unregistered one

Asking to recover an **unknown** email answers normally with a made-up code id, which is what hides whether an address is registered. But asking for an email that *is* registered and has not set a password yet is rejected outright instead.

The two answers differ, so anyone can sort addresses into "has an account here" and "does not" by reading the response. That defeats the protection the made-up code id was added to provide.

**To decide:** whether the not-yet-onboarded case should answer normally too, exactly like an unknown address.

#### 3. The countdown we hand back can be wrong

The answer to step 1 always states the full code lifetime. In the case where someone has asked too many times, the code's real expiry is deliberately not extended — but the answer still reports a full fresh lifetime.

A page showing that countdown tells the person they have far longer than they really do, and the code stops working while the timer is still running.

**To decide:** whether the answer should report the code's real remaining time rather than the configured maximum.

#### 4. Ordinary situations answer with a server error

Several everyday things return a `500 INTERNAL_SERVER_ERROR`, which says something broke on our side when nothing did:

- Entering a code id we no longer hold — an old email, a stale browser tab, or a code already used.
- Submitting a reset token that has already been used. This is the ordinary double-submit: a second click, or a refreshed page after a successful reset.

**To decide:** the right answer for each. Both look like "this is no longer valid, please start again" rather than a failure.

#### 5. The request counter never resets except on success

The counter limiting how often someone can ask for a code is cleared only when a code is finally entered correctly. Generating a brand-new code does not clear it.

So the limit is not really "requests per code" but "requests ever, until a successful recovery". Someone who abandons recovery a few times, months apart, quietly reaches the limit — and from then on their codes stop being extended, with nothing explaining why.

**To decide:** whether this counter should reset whenever a new code is issued, the way the wrong-attempt counter already does.

#### 6. The code may not live long enough to be usable

The code lifetime defaults to **45 seconds**, with the resend cooldown at 15. That has to cover the email being sent, delivered, noticed, opened, and the code typed in.

For most people that is not long enough, and email delivery alone can exceed it. A code that routinely expires before it arrives pushes people into asking repeatedly, which is exactly what the request limit in gap 5 then penalises.

**To decide:** what the code lifetime should be for an emailed code, given that the phone and email verification codes elsewhere in the product may want different values.

{% include abbreviations.md %}
