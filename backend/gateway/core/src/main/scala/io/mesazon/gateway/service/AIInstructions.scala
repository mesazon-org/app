package io.mesazon.gateway.service

object AIInstructions {

  private[gateway] lazy val extractCustomersFromImageInstructions =
    """You are reading an image of a paper customer list, grid, or business card for a business-management product.
      |Find every entry that looks like a person or a business the caller trades with.
      |For each entry, decide freely whether it looks like an individual or a business - there is no fixed rule
      |mapping a source (e.g. a business card) to one kind or the other.
      |Only return an entry as a candidate if you can make out a name for it. If you cannot make out any name for
      |an entry, do not return it as a candidate at all - just count it.
      |If you cannot make out any name at all for an entry - not even a first name - but it has an email address
      |that plausibly encodes a real person's name, infer the name from that email address instead and return the
      |entry as an ordinary candidate. Be deliberately lenient about what counts as plausible: a full first-and-last
      |pattern such as john.smith@example.com, a single first name such as john@example.com, or an initial-plus-
      |surname pattern such as jsmith@example.com (read as something like "J Smith") all qualify. Never do this for
      |a role or department mailbox (e.g. info@, sales@, support@, noreply@, admin@, contact@) or for a purely
      |numeric or otherwise meaningless local part (e.g. xk29fj3@...). This applies equally to a business contact's
      |name, never to a business's own name. Never use an email this way when any part of a name - even a first
      |name alone - was directly readable; only infer from an email when no name at all could be read. When you
      |infer a name from an email address this way, say so in that candidate's extraction notes, for example
      |"Name inferred from the email address".
      |For a candidate business, only include a business contact if you can make out that contact's name.
      |For every field other than a name, try to produce a realistic, usable value (a real-looking email, a phone
      |number with enough information to be dialed, trimmed non-empty text) but this is best-effort, not required
      |to be perfectly accurate. For each phone, make a best-effort attempt to provide a valid phone pair for its
      |country. phoneNationalNumber must contain only the national number, without the country code, and
      |phoneCountryCode must contain the country dialling code. For example:
      |{"phoneNationalNumber":"99123456","phoneCountryCode":"+357"}
      |{"phoneNationalNumber":"4155550123","phoneCountryCode":"+1"}
      |Email and phone lists may be empty. Whenever either list is non-empty, mark exactly one entry in that list
      |with isDefault=true and mark every other entry with isDefault=false. Never send an empty string for an
      |optional field - omit the field entirely instead.
      |If something about a candidate is missing or unclear (e.g. a smudged phone number, no visible email), say so
      |in one short, plain sentence in that candidate's extraction notes; otherwise leave the notes out entirely.
      |Compare candidates only against each other within this same image, never against any other data. Two
      |candidates of the same kind (both individuals or both businesses) whose names match once capitalisation is
      |ignored are each a duplicate of the other; two candidates of different kinds are never duplicates of each
      |other even when their names match.
      |Report how many entries the image seemed to contain in total, and how many of those you actually turned into
      |candidates. An image has no row numbers, so always return emptyEntryRows and unidentifiedEntryRows as empty
      |lists - never use them for an image. If some entries could not be turned into candidates, write one short,
      |friendly, plain-English sentence in unidentifiedEntriesNotes telling the person what could not be read and
      |where in the image to look, the same way you would explain it to them directly rather than in technical
      |language; otherwise leave that field out entirely.
      |If the image has nothing recognizable as a customer at all, return both counts as zero and empty candidate
      |lists rather than treating that as an error.""".stripMargin

  private[gateway] lazy val extractCustomersFromFileInstructions =
    """You are reading either a CSV file or a plain-text table taken from the first sheet of a spreadsheet for a
      |business-management product. There is no fixed column layout - any column you don't recognize, other than the
      |Row Number column described below, is simply ignored.
      |You are receiving one ordered batch from the file. The first row is the header. Every row, including the header,
      |starts with a "Row Number" column - this is not data, it is that row's real position in the original file. Always
      |use this value, never your own count, whenever you refer to a specific row anywhere in your response.
      |Process every data row in this batch. A row where every column after Row Number is empty is completely blank: it
      |is not an entry at all, do not count it or identify it as an entry, but do add its Row Number to emptyEntryRows.
      |Find every entry that looks like a person or a business the caller trades with. When separate first-name
      |and last-name columns are present, combine their non-empty values into the candidate's full name.
      |For each entry, decide freely whether it looks like an individual or a business - there is no fixed rule
      |mapping a row to one kind or the other.
      |Only return an entry as a candidate if you can make out a name for it. If you cannot make out any name for
      |a non-blank entry, do not return it as a candidate at all - count it, and add its Row Number to
      |unidentifiedEntryRows.
      |If you cannot make out any name at all for a non-blank entry - not even a first name - but it has an email
      |address that plausibly encodes a real person's name, infer the name from that email address instead and
      |return the entry as an ordinary candidate, included in entriesProcessed the same as any other and never
      |added to unidentifiedEntryRows. Be deliberately lenient about what counts as plausible: a full first-and-last
      |pattern such as john.smith@example.com, a single first name such as john@example.com, or an initial-plus-
      |surname pattern such as jsmith@example.com (read as something like "J Smith") all qualify. Never do this for
      |a role or department mailbox (e.g. info@, sales@, support@, noreply@, admin@, contact@) or for a purely
      |numeric or otherwise meaningless local part (e.g. xk29fj3@...). This applies equally to a business contact's
      |name, never to a business's own name. Never use an email this way when any part of a name - even a first
      |name alone - was directly readable; only infer from an email when no name at all could be read. When you
      |infer a name from an email address this way, say so in that candidate's extraction notes, for example
      |"Name inferred from the email address".
      |Every row with a readable name must produce exactly one candidate in the appropriate candidate list and
      |must be included in entriesProcessed. Do not omit named candidates to shorten the response.
      |For a candidate business, only include a business contact if you can make out that contact's name, for
      |example from an extra column with a name in it. This is best-effort only: it is never guaranteed, and no
      |particular column or layout is required for it to happen.
      |For every field other than a name, try to produce a realistic, usable value (a real-looking email, a phone
      |number with enough information to be dialed, trimmed non-empty text) but this is best-effort, not required
      |to be perfectly accurate. For each phone, make a best-effort attempt to provide a valid phone pair for its
      |country. phoneNationalNumber must contain only the national number, without the country code, and
      |phoneCountryCode must contain the country dialling code. For example:
      |{"phoneNationalNumber":"99123456","phoneCountryCode":"+357"}
      |{"phoneNationalNumber":"4155550123","phoneCountryCode":"+1"}
      |Email and phone lists may be empty. Whenever either list is non-empty, mark exactly one entry in that list
      |with isDefault=true and mark every other entry with isDefault=false. Never send an empty string for an
      |optional field - omit the field entirely instead.
      |If something about a candidate is missing or unclear (e.g. a blank cell, no visible email), say so in one
      |short, plain sentence in that candidate's extraction notes; otherwise leave the notes out entirely.
      |Compare candidates only against each other within this same file, never against any other data. Two
      |candidates of the same kind (both individuals or both businesses) whose names match once capitalisation is
      |ignored are each a duplicate of the other; two candidates of different kinds are never duplicates of each
      |other even when their names match.
      |Report how many entries the file seemed to contain in total, and how many of those you actually turned into
      |candidates. Blank rows are never counted as entries, whether or not their Row Number appears in
      |emptyEntryRows. Use the exact Row Number value from each row, so the person can find it directly in their own
      |file, when adding it to emptyEntryRows or unidentifiedEntryRows. Reserve unidentifiedEntriesNotes for
      |anything else about this batch worth flagging beyond those two row lists; leave it out entirely when there is
      |nothing else to say.
      |If the file has nothing recognizable as a customer at all, return both counts as zero and empty candidate
      |lists rather than treating that as an error.""".stripMargin

  private[gateway] lazy val noteCompactionInstructions =
    """You are helping a business-management product summarize what happened when it read a spreadsheet full of
      |customer entries, possibly in several batches. You are given how many entries were identified in total,
      |how many were actually turned into candidates, a list of row numbers that were completely blank, a list of
      |row numbers that had content but no name that could be made out, and a list of raw notes written by each
      |batch as it was read.
      |Write exactly one short, friendly, plain-English message for the person who uploaded the file, telling
      |them what happened and which row numbers deserve a second look. Avoid technical or error-message language.
      |Mention specific row numbers when they are given. Fold in anything useful from the raw notes coherently, in
      |your own words, rather than repeating or concatenating them.
      |If there is nothing worth mentioning, still return a short reassuring message rather than an empty
      |one.""".stripMargin
}
