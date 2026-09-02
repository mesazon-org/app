{% comment %}
Kramdown abbreviation definitions, shared by every epic. Each one turns every
plain-text occurrence of the term in the page into an <abbr> with a hover
tooltip, so authors just write "OTP" and never mark anything up themselves.

Include this at the bottom of an epic. It renders nothing visible.

Adding an acronym here means adding it to pages/glossary.md too — the tooltip
and the glossary are meant to say the same thing.

Matching is case-sensitive and skips code spans, so `OTP` in backticks and the
"Http Error Responses" headings are left alone.
{% endcomment %}

*[JWT]: JSON Web Token
*[OTP]: One-Time Passcode
*[RFC]: Request for Comments — an internet standards document
*[SMS]: Short Message Service — a text message
*[UUID]: Universally Unique Identifier
