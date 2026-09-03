---
title: Sign In
---

# Sign In

### Overview

Someone who already has an account should be able to get back into it with their email address and password, and pick up wherever they left off.

### Related / Out of scope

- **Related** — [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}). Signing in works before onboarding is finished, on purpose: it is how someone who stopped halfway comes back and carries on.
- **Related** — [Forgot Password]({{ site.baseurl }}{% link epics/02-forgot-password.md %}). Where someone goes when the password is the thing they cannot supply.
- **Out of scope** — Keeping a session alive once it has started. Renewing an expired session is its own journey.
- **Not built yet** — There is no "remember this device", no list of active sessions, and no way to sign out from somewhere else. Signing in is all or nothing: it starts one session and ends every other.

### Requirements across the epic

Signing in is a single action, but two things happen behind it: the credentials are checked, and then a session is started. The rules below hold across both.

#### Functional

1. Anyone who has set a password can sign in, even if they have not finished onboarding. That is deliberate — it is how someone resumes a half-finished sign up.
2. Signing in starts exactly one session. Every session the account had before ends at that moment, on every other device.
3. The answer always says how far through onboarding the account is, so the app knows whether to open the product or drop the person back into the step they stopped at.

#### Non-functional

1. Email and password are never sent in the body. They travel in the request's authorization header, using HTTP Basic authentication, which means the connection must be encrypted.
2. A refusal never says *why*. A wrong password, an unknown email address, and a locked account all come back the same way, so sign in cannot be used to discover which addresses have accounts.
3. Repeated failures are counted per account. Past a limit the account stops accepting sign in for a while, even with the right password.
4. The password itself is never stored, and never compared as text. Only a scrambled form of it is kept, and the check happens against that.

### User flow

1. [User Signs In](#1-user-signs-in)

### Prerequisites

Every account carries an **onboard stage** saying how far through sign up it has got. Sign in accepts `PasswordProvided`, `PhoneVerification`, and `PhoneVerified` — in other words, from the moment a password exists. The [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}) epic explains the stages in full.

One counter sits behind this flow:

| **Counter** | **What it counts** |
| --- | --- |
| Sign-in attempts | How many times sign in has been tried for this account. Cleared the moment a password is accepted. |

### 1. User Signs In

**Who can reach this step: anyone — no existing session required.** The account must be at `PasswordProvided`, `PhoneVerification`, or `PhoneVerified`.

- User opens the sign-in page and enters their email address and password.
- We check the credentials, end any session the account already had, and start a new one.
- The app sends them onward — into the product if onboarding is finished, or back to the step they stopped at if not.

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User signs in with the right password, onboarding finished | - Credentials are accepted - Every existing session for the account is ended - A new session is started - The failed-attempt counter is cleared - User lands in the product |
| 2. User signs in with the right password, onboarding not finished | - Exactly as scenario 1 - The answer reports the stage the account is at - App sends the user back to the onboarding step they stopped at, rather than into the product |
| 3. User signs in with the wrong password | - Rejected - The attempt is counted - No session is started and nothing existing is ended |
| 4. User signs in with an email address that has no account | - Rejected in exactly the same way as a wrong password, so the two cannot be told apart |
| 5. User gets the password wrong too many times | - The account stops accepting sign in for a set period - The right password is rejected too, for as long as the block lasts - See [gap 1](#1-a-locked-account-can-be-kept-locked-indefinitely) |
| 6. User tries to sign in before setting a password | - Rejected, because there is nothing to check against - See [gap 2](#2-sign-in-reveals-whether-an-email-address-has-an-account) |
| 7. User sends no credentials at all | - Rejected |

#### Requirements

1. The right email and password start a session, provided the account has got as far as setting a password.
2. A wrong password and an unknown email address are refused identically, and neither reveals which it was.
3. Every failed attempt is counted against the account. Past the limit the account is blocked for a period, and during that block even the correct password is refused.
4. A successful sign in clears the failed-attempt count, so ordinary mistyping never accumulates towards a block.
5. Starting a session ends every other session the account had.

#### Request / Response / Outcome

**Request**

Request body is empty. The credentials travel in the request's authorization header as HTTP Basic authentication, which carries the two values below.

| **Field Name** | **Type** | **Format** | **Description** |
| --- | --- | --- | --- |
| Email | `String` | Standardised by [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) & [RFC 6854](https://www.rfc-editor.org/rfc/rfc6854) | The email address on the account. The user half of the credentials. |
| Password | `String` | As set on the account | The account's password. The password half of the credentials. |

**Response**

| **Field Name** | **Type** | **Format** | **Description** |
| --- | --- | --- | --- |
| Access Token | `String` | JWT | Signs the person in for ordinary requests. |
| Access Token Expires In Seconds | `Long` | Seconds | How long the access token stays usable. |
| Refresh Token | `String` | JWT | Used to get a new access token once the current one runs out. |
| Onboard Stage | `OnboardStage` | One of the five stage values | How far through onboarding the account is, so the app knows where to send the person next. |

**Outcome**

- Every token the account already held is deleted. That ends any session on another device, and also invalidates an outstanding password-reset token if one was in flight.
- A fresh access and refresh token are issued, and the refresh token is saved so it can be checked and revoked later.
- The failed-attempt counter for the account is cleared.
- On any refusal nothing is started and nothing existing is ended. The only change is that the attempt counter goes up.

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

The block lasts a set period measured from the **last** attempt, and every attempt — including ones made while the account is already blocked — resets that clock. The counter is only ever cleared by a successful sign in, which is exactly what the block prevents.

So anyone who knows an email address can keep trying passwords on a slow loop and hold the real owner out forever. The owner has no way to clear it themselves; the only escape is a window of silence long enough for the block to lapse.

The defence against guessing passwords has become a way to deny someone their account.

**To decide:** whether attempts made during a block should extend it at all, whether the counter should decay on its own, and whether the owner should be given a way out — a successful password reset clearing the block would be the obvious candidate.

#### 2. Sign in reveals whether an email address has an account

A wrong password and an unknown address are refused identically, which is correct and deliberate. But an address belonging to an account that has not set a password yet is refused *differently* — a distinct refusal, telling the caller the address is registered but not yet finished.

That check also runs before the password is looked at, so no password is needed to ask the question. Anyone can sort a list of addresses into "has an account here" and "does not".

This is the same leak as [gap 2 in Forgot Password]({{ site.baseurl }}{% link epics/02-forgot-password.md %}#2-a-registered-email-can-be-told-apart-from-an-unregistered-one), reached by a different door, and the two should be answered together.

**To decide:** whether a not-yet-onboarded account should be refused exactly like a wrong password.

#### 3. Blocking counts the account, not whoever is trying

The attempt counter hangs off the account alone. Nothing distinguishes the owner mistyping on their own phone from a stranger guessing from the other side of the world, so the stranger's failures are what lock the owner out. This is what makes gap 1 reachable by anyone who knows an email address.

**To decide:** whether attempts should also be counted per source, so that a familiar device is not punished for a stranger's guessing.

#### 4. Signing in silently signs you out everywhere else

One session per account is a deliberate choice, but nothing tells anyone it happened. Signing in on a phone ends the session on a laptop with no warning beforehand and no notice afterwards — the laptop simply stops working at some later moment.

Because a session ending looks the same whether it was you signing in elsewhere or someone else signing in as you, the one signal that would reveal a stolen password goes unnoticed.

**To decide:** whether ending other sessions should be announced — a notice on the new device, an email to the account, or both — and whether more than one session should be allowed at all.

{% include abbreviations.md %}
