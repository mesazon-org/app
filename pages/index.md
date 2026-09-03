---
title: Home
---

# Mesazon

**Mesazon is a business management platform** — a practical toolbox for the everyday work of running a small business.

A business owner signs up, proves the email address and phone number are theirs, and creates their **organization**. From there they keep a **customer book** of the people and companies they trade with, upload things like a logo, and manage the work that flows between them.

These pages describe the product from the outside in: what a person can do, in what order, and what the business expects to happen at each step. They are written for anyone — no engineering background needed.

## How it fits together

<figure class="diagram">
  <img src="{{ '/assets/mesazon-architecture.drawio.svg' | relative_url }}"
       alt="Mobile app talking to the API gateway, which reads and writes the database and calls out to the language model and the messaging services.">
</figure>

People use Mesazon through a **mobile app**. Everything it does goes through one **API gateway** — the single front door that checks who is asking, decides whether they are allowed, and does the work. Business information is kept in a **database**.

The gateway also talks to services outside Mesazon: a **language model** for the assisted replies, and the **messaging services** — WhatsApp, Telegram and Viber — for conversations with customers.

The epics below describe what happens inside that gateway, one journey at a time.

## Epics

An **epic** covers one area of the product end to end: the steps a person goes through, the situations each step has to handle, and the rules behind them.

| Epic | What it covers |
| --- | --- |
| [User Onboarding]({{ site.baseurl }}{% link epics/01-user-onboarding.md %}) | Signing up with an email, verifying it, setting a password, adding a name and phone number, and verifying the phone. Everything up to the point where someone can create an organization. |
| [Forgot Password]({{ site.baseurl }}{% link epics/02-forgot-password.md %}) | Getting back into an account after forgetting the password: asking for a code by email, entering it, and setting a new password. |
| [Sign In]({{ site.baseurl }}{% link epics/03-sign-in.md %}) | Getting back into an account with an email address and password, and being sent on to the right place afterwards. |

Each epic also closes with **Known gaps and open questions** — the decisions that have not been made yet, kept separate from what the product does today so the two are never confused.

## Glossary

The [Glossary]({{ site.baseurl }}{% link glossary.md %}) explains the acronyms and the handful of terms the epics rely on — what an *onboard stage* is, what a *one-time passcode* does, and so on.

Inside an epic you can also hover over any acronym to see what it stands for, without leaving the page.
