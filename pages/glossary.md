---
title: Glossary
---

# Glossary

Words and short forms used across the epics. Anywhere an acronym appears in an epic you can hover over it to see what it stands for; this page is the full list.

## Acronyms

| **Acronym** | **Stands for** | **What it means here** |
| --- | --- | --- |
| JWT | JSON Web Token | The format of the tokens that keep someone signed in. A signed piece of text the app can check without looking anything up. |
| OTP | One-Time Passcode | A short code we send to someone to prove they own an email address or a phone number. It works once, and stops working after it expires. |
| RFC | Request for Comments | An internet standards document. We use them to say exactly what counts as a valid email address, rather than inventing our own rule. |
| SMS | Short Message Service | A text message to a mobile phone. How we send a passcode when verifying a phone number. |
| UUID | Universally Unique Identifier | A long random id, written as 36 characters, used to point at one specific thing without anyone being able to guess it. |

## Terms

| **Term** | **What it means** |
| --- | --- |
| Epic | One area of the product, described from the user's side: what a person can do, in what order, and what the business expects to happen. The epics are the pages listed on the [home page]({% link index.md %}). |
| Onboard stage | How far through sign up an account has got. Every account has exactly one, and each step only accepts people at the right stage. The stages run `EmailVerification` → `EmailVerified` → `PasswordProvided` → `PhoneVerification` → `PhoneVerified`. |
| Passcode | See **OTP** above. The epics say "passcode" in prose and "OTP" in the field tables, because that is what the field is called. |
| Waiting period | The short gap after we send a passcode during which asking for another one sends nothing. The existing passcode is reused instead. Also called the resend cooldown. |
