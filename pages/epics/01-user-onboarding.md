---
title: User Onboarding
---

# User Onboarding

### Overview

Users should be able to sign up and create an account with their own email.

### Related / Out of scope

- **Related** — Signing in. Someone who stops partway through, for example after setting a password but before verifying their phone, comes back by signing in rather than starting sign up again.
- **Out of scope** — Resetting a forgotten password. That only applies once someone has set a password, and it is covered on its own.
- **Not built yet** — Finishing this flow lets someone create an organization. Joining an organization that already exists is not built, so it is not part of this flow today.

### Requirements across the epic

These hold true for the whole of sign up. Requirements that belong to a single step are listed with that step.

Throughout this epic, a **one-time passcode** (OTP) is a short code we send to someone to prove they own an email address or a phone number. Hover over any acronym to see what it stands for, or see the [glossary]({{ site.baseurl }}{% link glossary.md %}).

#### Functional

1. Everyone moves through the same five steps in the same order. Each step only accepts people at the right onboard stage, and turns away anyone else.
2. A passcode is six characters long and mixes letters and digits. It stops working once it expires.
3. Every passcode has its own id. To use a passcode the person sends back both the id and the code they received.
4. Someone who leaves partway through keeps their progress. Once they have set a password they come back by signing in; before that, they sign up again with the same email and carry on.
5. An email address belongs to one account only. Before we do anything with an email we strip surrounding spaces and lower-case it, so `Sam@Example.com ` and `sam@example.com` are the same person and cannot become two accounts.
6. A rejected passcode is reported with its own error code. Any other reason a step here refuses someone — a missing, invalid, or expired access token — gets the same error code used everywhere else in the product for a rejected access token.

#### Non-functional

1. Signing up and verifying an email do not require being signed in. We keep them safe by limiting how often a passcode can be resent, and by never revealing whether an email is already registered, rather than by asking people to log in first.
2. Asking for a new passcode too soon does not send another one. While the previous passcode is still inside its waiting period we reuse it and send nothing, but its expiry is pushed out again, so the person always gets the full window to use the code they were sent.
3. If a verification email or text fails to send, we try again a few times, waiting a little longer between each attempt. If it still fails, the request fails and the person is told. The welcome email is the exception: if it cannot be sent, the person carries on unaffected.
4. A fixed passcode can be switched on for local development, so developers do not need a real inbox or phone. It is controlled by an environment setting and must stay switched off in production.
5. A wrong passcode is counted, separately for each passcode issued. After 5 wrong tries in a row on the same passcode — on either the email step or the phone step — we delete it and reject the next attempt exactly as we would an expired one, so it cannot be guessed by trying repeatedly. Successfully verifying a passcode clears its wrong-try count too, the same as issuing a genuinely new passcode does. Reusing an existing passcode because a resend landed inside its cooldown window does not count against this, and does not reset it either — only being issued a genuinely new passcode, or verifying one correctly, starts the count over. The person is not told how many tries they have left.
6. A passcode id we no longer hold is rejected exactly like an expired passcode — same response, and nothing to delete. This covers a stale browser tab, a reused link, an already-used passcode, or asking about phone verification when no passcode is outstanding at all.

### User flow

1. [User Sign's Up](#1-user-signs-up)
2. [User Verifies Email](#2-user-verifies-email)
3. [User Provides Password](#3-user-provides-password)
4. [User Provides Details](#4-user-provides-details)
5. [User Verifies Phone Number](#5-user-verifies-phone-number)

### Prerequisites

Before diving in the user flow steps we should first introduce the concept of onboard stage. All users will be assigned an onboard stage and only users will the right onboard stage will be able to perform actions. For every single step we will be documenting what stages are allowed and anything else will be rejected.

#### Onboard Stages Example:

| **Field Name** | **Type** | **Values** | **Description** |
| --- | --- | --- | --- |
| onboardStage | `OnboardStage` | `EmailVerification` `EmailVerified` `PasswordProvided` `PhoneVerification` `PhoneVerified` | Users onboard stages |

A new user's account is created directly at `EmailVerification` — there is no separate "no stage yet" value.

### 1. User Sign's Up

**Allowed onboard stages: \[EmailVerification, EmailVerified\]** (a brand-new email has no account yet, so no stage check applies)

- Users redirects from sign-in form to sign-up page.
- User fills sign's up page with email

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User sign's up for the first time | - No account exists yet - Generate new OTP (One Time Passcode) with expiration time - User should receive an email containing the new generated OTP - Frontend receives the new OTP ID - Users should provide the OTP contained in email and alongside OTP ID provided to fronted should request backend to verify Users email - Redirects user to verify OTP page |
| 2. User signs up with an email they already verified, but have not set a password for | - Allowed onboardStages (EmailVerification, EmailVerified) - Onboard stage is reset to EmailVerification (must verify again) - Generate new OTP (One Time Passcode) with expiration time - User should receive an email containing the new generated OTP - Frontend receives the new OTP ID - Users should provide the OTP contained in email and alongside OTP ID provided to fronted should request backend to verify Users email - Redirects user to verify OTP page |
| 3. User signs up with an email that already been requested to sign up with OTP expired | - Allowed onboardStages (EmailVerification, EmailVerified) - Generate an OTP (One Time Passcode) with expiration time - User should receive an email containing the generated OTP - Frontend receives the new OTP ID - Users should provide the OTP contained in email and alongside OTP ID provided to fronted should request backend to verify Users email - Redirects user to verify OTP page |
| 4. User signs up with an email that already been requested to sign up while still inside the resend-cooldown window | - Allowed onboardStages (EmailVerification, EmailVerified) - Use existing generated OTP - User should **not** receive an email (already sent, cooldown still active) - Expiration time of the existing OTP is extended (prevents email scanning attacks) - Frontend receives the existing OTP ID - Redirects user to verify OTP page |
| 5. User signs up with an email that already has a password set | - Users with onboard stage other than (EmailVerification, EmailVerified) — i.e. PasswordProvided onwards - Onboard stage is **not** changed and the email cannot be re-verified this way - Users should not be notified that email already exists in database, instead they should redirect to validate OTP page but no email will be sent. (This prevents email scanning attacks) - Frontend receives a fake OTP ID - Redirects user to verify OTP page |

#### Requirements

1. When someone signs up with an email for the first time, we create their account, generate a passcode, and email it to them.
2. Someone who has not finished verifying their email can ask us to send the passcode again.
3. If someone verified their email but has not set a password yet, signing up again with that email starts verification over. Their onboard stage goes back to `EmailVerification` and they verify once more.
4. Once someone has set a password, signing up again with their email does nothing at all. They cannot re-verify this way, and they cannot be sent back to an earlier step. We reply exactly as we would for a brand-new email, but send no email and save nothing, so sign up can never be used to find out whether an email is registered.

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

- A brand-new email gets an account created at `EmailVerification`, a new passcode saved against it, and a verification email sent.
- An email already part-way through sign up has its stage reset to `EmailVerification`. The existing passcode is reused if it is still inside its waiting period, or replaced and emailed if not. Either way its expiry is pushed out to a full fresh window.
- An email that already has a password set changes nothing: nothing saved, nothing sent, stage untouched.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 2. User Verifies Email

**Allowed onboard stages: \[EmailVerification\]**

- User redirects from sign up page to verify OTP page for verifying their email
- User submits OTP sent to their email (when eligible) and frontend sent OTP ID along side OTP
- Client will receive session for the ongoing requests

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User received email with OTP and submit it to the form | - User OTP is verified against the one stored - User onboard stage should be updated to `EmailVerified` - The wrong-attempt count for this passcode is cleared - All of the user's existing tokens are revoked and a fresh access/refresh token pair is issued - Redirects user to providing password page |
| 2. User provides a wrong OTP, still under the attempt limit | - User receives a plain bad request response, with no detail about why - The passcode is not deleted and remains usable |
| 3. User provides an expired OTP | - The OTP is deleted - User receives the same plain bad request response as any other rejection here |
| 4. User submits the wrong OTP for the sixth time in a row | - Rejected without the OTP even being checked - The OTP is deleted, exactly as if it had expired - User receives the same plain bad request response as any other rejection here - Getting a new OTP means signing up again with the same email (see [step 1](#1-user-signs-up)) |
| 5. User submits a passcode id we hold no record of — an old browser tab, a reused link, a passcode already used, or the fake id handed back when signing up with an email that already has a password (see [step 1, scenario 5](#1-user-signs-up)) | - Rejected with the same plain bad request response as any other rejection here - No passcode is deleted, because none was found |

#### Requirements

1. When someone enters the correct passcode, we mark their email as verified and move them to `EmailVerified`.
2. Verifying an email starts a fresh session. Any sign-in the person had before is cancelled, so only the newest one keeps working.
3. Every way this step can go wrong — a wrong passcode, an expired one, too many wrong tries in a row, or a passcode id we don't recognise — gets back exactly the same plain bad-request response, with no detail about which of these happened. This keeps the guarantee from [step 1, scenario 5](#1-user-signs-up): the fake passcode id handed back for an email that already has a password must fail here in a way nobody can tell apart from a real, active passcode being guessed wrong, or sign up would leak whether an email is already registered.
4. A wrong passcode is counted. After 5 wrong tries in a row, we delete it — the same as when it expires — so guessing cannot go on forever.
5. Submitting a passcode id we do not recognise is rejected the same way, and nothing is deleted because there is nothing to delete.
6. Verifying correctly clears the wrong-attempt count for that passcode, so nothing is left behind once sign up moves on.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| OTP ID | `UUID` | Canonical 36-character form | ✅ | A random generated UUID assigned to specific OTP |
| OTP | `String` | Exactly 6 characters, uppercase letters and digits only | ✅ | A random generated String including letters and digits. |

**Response**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Access Token | `String` | JWT | ✅ | Signs the person in so they can carry on through the remaining steps. |
| Access Token Expires In Seconds | `Long` | Whole seconds | ✅ | How long the access token stays usable. |
| Refresh Token | `String` | JWT | ✅ | Used to get a new access token once the current one runs out. |
| Onboard Stage | `OnboardStage` | `EmailVerified` | ✅ | The stage the person has moved to. |

**Outcome**

- The stage moves to `EmailVerified` and the passcode is deleted, so it cannot be used twice.
- The wrong-attempt count for this passcode is cleared, the same as when a genuinely new passcode is issued.
- Every token the person already held is revoked, a fresh access and refresh token are issued, and the refresh token is saved.
- A wrong passcode changes nothing and leaves the passcode usable; the response is the same plain bad request used for every other rejection reason here.
- An expired passcode is deleted, then the same plain bad request response is returned.
- After 5 wrong tries in a row, the passcode is deleted and any further attempt against it gets the same plain bad request response. The person must sign up again to receive a new one.
- Submitting a passcode id we hold no record of gets the same plain bad request response; nothing is deleted because there is nothing to delete.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 400 | `BAD_REQUEST_ERROR` | - OTP was wrong - OTP expired - Too many wrong attempts - OTP id not recognized |
| 403 | `FORBIDDEN_ERROR` | - Invalid onboard stage |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 3. User Provides Password

**Allowed onboard stages: \[EmailVerified\]**

- User provides new password for the email provided

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User submits password request | - Users new password is hashed and stored in database - User onboard stage should be updated to `PasswordProvided` - User should be able to sign-in - User should receive a welcoming email (best-effort — never blocks or fails the request) |

#### Requirements

1. Once their email is verified, the person sets a password. We store it scrambled, never as they typed it.
2. We then move them to `PasswordProvided`. From this point on they can sign in.
3. We try to send them a welcome email.

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

- The password is scrambled and saved. We never keep it as the person typed it.
- The stage moves to `PasswordProvided`. From here the person can sign in.
- A welcome email is attempted. If it cannot be sent we record that and carry on; the person is never held up by it.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Invalid onboard stage |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 4. User Provides Details

**Allowed onboard stages: \[PasswordProvided, PhoneVerification\]**

- User should provide details about their account

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User submits details request | - Users details stored to database - User onboard stage should be updated to `PhoneVerification` - User should receive an SMS with OTP - Redirects User to Verify OTP page |
| 2. User re-submits details request while an existing OTP is still inside the resend-cooldown window | - Submitted details are stored (this step can be used to change the phone number) - Existing OTP is reused, no new SMS is sent while inside the cooldown - Redirects User to Verify OTP page |
| 3. User re-submits details request once the resend-cooldown has passed | - Submitted details are stored - A new OTP is generated - User should receive a new SMS with OTP - Redirects User to Verify OTP page |

#### Requirements

1. The person gives their full name and phone number. We save those details, move them to `PhoneVerification`, and text them a passcode.
2. They can come back to this step to correct their phone number. We save whatever they send us each time.

#### Request / Response / Outcome

**Request**

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| Full Name | `String` | 1–255 characters, trimmed | ✅ | The user full name |
| Phone Number | `PhoneNumber` | — | ✅ | The number to send the passcode to. See **PhoneNumber** below |

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

- The name and phone number are saved, replacing whatever was stored before. The stage moves to `PhoneVerification`.
- A passcode is saved and texted to the number given, or the existing one is reused with no text sent if it is still inside its waiting period.

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Invalid onboard stage |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### 5. User Verifies Phone Number

**Allowed onboard stages: \[PhoneVerification\]**

- User redirects from provides details page to verify OTP page for verifying their phone number
- User submits SMS OTP sent to their phone and frontend sent OTP ID along side OTP

#### Business Scenarios

| **Scenarios** | **Requirements** |
| --- | --- |
| 1. User received SMS with OTP and submit it to the form | - User OTP is verified against the one stored - User onboard stage should be updated to `PhoneVerified` - The wrong-attempt count for this passcode is cleared - Redirects user to create Organization page |
| 2. User provides wrong or expired OTP | - User receives a message about what went wrong |
| 3. User reloads the verify page while a passcode is still outstanding | - Looking up the pending verification returns the OTP ID and how long it stays valid - No new OTP is generated and no SMS is sent - User carries on entering the passcode they already received, without re-submitting their details |
| 4. User submits the wrong OTP for the sixth time in a row | - Rejected without the OTP even being checked - The OTP is deleted, exactly as if it had expired - User receives the same message as an expired OTP - Getting a new OTP means going back to [providing details](#4-user-provides-details) to trigger a resend |
| 5. User submits a passcode id we hold no record of — an old browser tab, a reused link, or a passcode already used | - Rejected with the same message as an expired passcode - No passcode is deleted, because none was found - Getting a new OTP means going back to [providing details](#4-user-provides-details) to trigger a resend |
| 6. User opens the verify-phone page when no passcode is outstanding at all | - Rejected with the same message as an expired passcode - Getting a new OTP means going back to [providing details](#4-user-provides-details) to trigger a resend |

#### Requirements

1. When someone enters the correct passcode from the text message, we move them to `PhoneVerified`.
2. Sign up is now finished and they can create an organization.
3. We reject the passcode if it is wrong, if it has expired, or if the account is not at a stage where verifying is allowed.
4. Someone who reloads this page can look up the passcode they are already waiting on, and how long it stays valid, without going back to fill in their details again.
5. A wrong passcode is counted. After 5 wrong tries in a row, we delete it and reject the next attempt exactly as we would an expired one.
6. Submitting a passcode id we do not recognise gets the same answer as an expired passcode, not a server error.
7. Opening this page with no passcode outstanding at all also gets the same answer as an expired passcode.
8. Verifying correctly clears the wrong-attempt count for that passcode, so nothing is left behind once sign up is finished.

#### Request / Response / Outcome

**Request**

Submitting the passcode:

| **Field Name** | **Type** | **Constraint** | **Required** | **Description** |
| --- | --- | --- | --- | --- |
| OTP ID | `UUID` | Canonical 36-character form | ✅ | A random generated UUID assigned to specific OTP |
| OTP | `String` | Exactly 6 characters, uppercase letters and digits only | ✅ | A random generated String including letters and digits. |

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

- The stage moves to `PhoneVerified` and the passcode is deleted. Sign up is finished and the person can create an organization.
- The wrong-attempt count for this passcode is cleared, the same as when a genuinely new passcode is issued.
- A wrong passcode changes nothing and leaves the passcode usable. An expired one is deleted before the request is refused.
- After 5 wrong tries in a row, the passcode is deleted and any further attempt against it is rejected the same way an expired passcode is. The person must go back to providing details to receive a new one.
- Submitting or looking up a passcode id we hold no record of — including opening this page with nothing outstanding — is rejected the same way an expired passcode is, and nothing is deleted because there is nothing to delete.
- Looking up a passcode normally changes nothing, but it deletes the passcode when it is close enough to expiry — see [gap 3](#3-opening-the-phone-verification-page-can-destroy-a-usable-passcode).

#### Http Error Responses

| **Http Code** | **Code** | **Description** |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | - Form validation error |
| 400 | `BAD_REQUEST_ERROR` | - OTP was wrong |
| 401 | `UNAUTHORIZED_OTP_ERROR` | - OTP expired - Too many wrong attempts - OTP id not recognized - No OTP outstanding |
| 401 | `UNAUTHORIZED_ERROR` | - The access token is missing, invalid, or has expired |
| 403 | `FORBIDDEN_ERROR` | - Invalid onboard stage |
| 500 | `INTERNAL_SERVER_ERROR` | - Unexpected error |

### Known gaps and open questions

Decisions this epic has not made yet. Everything above describes what the product does today; everything here does **not** exist and needs a product answer before it can be built.

#### 1. A passcode's life can be extended without limit

Every sign-up request pushes the passcode's expiry out to a full fresh window, including the requests that reuse the existing passcode rather than sending a new one. Nothing caps the total. Someone calling sign up on a loop keeps the same passcode alive indefinitely, which combined with the now-limited guesses per passcode still gives an attacker unbounded fresh passcodes to try against, just not unbounded tries against any single one.

**To decide:** a hard ceiling on how long one passcode can live no matter how often it is renewed, and whether a renewal past that point should issue a fresh passcode instead.

#### 2. Two accounts can verify the same phone number

Email is unique across accounts and enforced by the database. Phone number is not — nothing stops two accounts completing sign up on the same number. Customer records in the address book *do* have a phone uniqueness rule, so this reads more like an oversight than a decision.

**To decide:** whether a phone number may belong to more than one account. If not, what the second person is told, and what happens to the accounts already sharing a number today.

#### 3. Opening the phone verification page can destroy a usable passcode

Looking up an outstanding phone passcode applies a stricter expiry rule than submitting one does. If the passcode is close enough to expiry to be inside the resend window, the lookup deletes it and reports it as expired — even though submitting that same passcode directly would still have worked.

**To decide:** whether the lookup should ever delete a passcode, or only report how long is left and leave it to the person to use or replace.

{% include abbreviations.md %}
