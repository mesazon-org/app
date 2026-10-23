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
      |The addresses list may be empty and never holds more than two addresses. All five text fields are optional:
      |addressLine1, addressLine2, city, postalCode and country. Treat missing, null, empty and whitespace-only text
      |as empty. Keep a partial address when at least one text field has non-whitespace content, trim populated
      |values, and briefly note any missing or unclear fields in that candidate's extraction notes.
      |Return empty address fields as null or omit them as the response schema permits, never as empty strings.
      |Omit an address only when all five text fields are empty, keeping the rest of the candidate. If none remain,
      |return addresses: []. Before assigning address types or limiting the list, merge repeated addresses within
      |the same candidate. Addresses are equal when all five text fields match after trimming populated values
      |and treating missing, null, empty and whitespace-only fields as absent; ignore addressType when comparing.
      |Return each distinct address only once. If duplicates collapse to one address, mark it ShippingAndBilling.
      |Never merge addresses belonging to different candidates. Only distinct non-empty addresses count below.
      |When a candidate has exactly one non-empty address, always return it with addressType ShippingAndBilling,
      |whatever the source says about it.
      |When a candidate has exactly two non-empty addresses, return one as Shipping and the other as Billing as the
      |source shows: an address marked as where goods are delivered (e.g. "Deliver to", "Ship to") is Shipping and an
      |address marked as where invoices or bills go (e.g. "Invoice to", "Bill to") is Billing. When the source does
      |not say which is which, return the first as Shipping and the second as Billing, and say in that candidate's
      |extraction notes that the address types were assumed.
      |When a candidate has three or more non-empty addresses, return only two of them: the best shipping and billing
      |pair the source shows, or else the first two, typed one Shipping and one Billing by the same rule as for two
      |addresses, and say in that candidate's extraction notes that further addresses were left out.
      |If something about a candidate is missing or unclear (e.g. a smudged phone number, no visible email), say so
      |as briefly as possible in that candidate's extraction notes - a short phrase or clause, not a full sentence,
      |when a phrase suffices (e.g. "phone smudged, no email" rather than "The phone number is smudged and no email
      |is visible"); otherwise leave the notes out entirely.
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
      |If the image does not contain recognizable customer data, for example an unrelated scene or document,
      |do not invent customer entries. Return entriesIdentified and entriesProcessed as zero, with
      |customerIndividualCandidates, customerBusinessCandidates, emptyEntryRows and unidentifiedEntryRows all
      |empty. Set unidentifiedEntriesNotes to "AI could not detect relevant customer data in this image."
      |This is an empty extraction result, not an error.""".stripMargin

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
      |The addresses list may be empty and never holds more than two addresses. All five text fields are optional:
      |addressLine1, addressLine2, city, postalCode and country. Treat missing, null, empty and whitespace-only text
      |as empty. Keep a partial address when at least one text field has non-whitespace content, trim populated
      |values, and briefly note any missing or unclear fields in that candidate's extraction notes.
      |Return empty address fields as null or omit them as the response schema permits, never as empty strings.
      |Omit an address only when all five text fields are empty, keeping the rest of the candidate. If none remain,
      |return addresses: []. Before assigning address types or limiting the list, merge repeated addresses within
      |the same candidate. Addresses are equal when all five text fields match after trimming populated values
      |and treating missing, null, empty and whitespace-only fields as absent; ignore addressType when comparing.
      |Return each distinct address only once. If duplicates collapse to one address, mark it ShippingAndBilling.
      |Never merge addresses belonging to different candidates. Only distinct non-empty addresses count below.
      |When a candidate has exactly one non-empty address, always return it with addressType ShippingAndBilling,
      |whatever the source says about it.
      |When a candidate has exactly two non-empty addresses, return one as Shipping and the other as Billing as the
      |source shows: an address marked as where goods are delivered (e.g. "Deliver to", "Ship to") is Shipping and an
      |address marked as where invoices or bills go (e.g. "Invoice to", "Bill to") is Billing. When the source does
      |not say which is which, return the first as Shipping and the second as Billing, and say in that candidate's
      |extraction notes that the address types were assumed.
      |When a candidate has three or more non-empty addresses, return only two of them: the best shipping and billing
      |pair the source shows, or else the first two, typed one Shipping and one Billing by the same rule as for two
      |addresses, and say in that candidate's extraction notes that further addresses were left out.
      |If something about a candidate is missing or unclear (e.g. a blank cell, no visible email), say so as briefly
      |as possible in that candidate's extraction notes - a short phrase or clause, not a full sentence, when a
      |phrase suffices (e.g. "phone smudged, no email" rather than "The phone number is smudged and no email is
      |visible"); otherwise leave the notes out entirely.
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
      |If this file batch does not contain recognizable customer data, for example an unrelated table or document,
      |do not invent customer entries from arbitrary headings or unrelated content. Return entriesIdentified and
      |entriesProcessed as zero, with customerIndividualCandidates, customerBusinessCandidates, emptyEntryRows and
      |unidentifiedEntryRows all empty. Set unidentifiedEntriesNotes to
      |"AI could not detect relevant customer data in this file." This is an empty extraction result, not an error.
      |This rule does not apply to recognizable customer entries whose names could not be read: count and report
      |those as unidentified entries using their real row numbers as described above.""".stripMargin

  private[gateway] lazy val extractCatalogueItemsFromImageInstructions =
    """You are reading an image that may contain catalogue items and variants for a business-management product.
      |Return exactly one CatalogueFileExtract JSON object with this shape:
      |{
      |  "entriesIdentified": 1,
      |  "entriesProcessed": 1,
      |  "candidates": [
      |    {
      |      "name": "Item name",
      |      "description": null,
      |      "variants": [
      |        {
      |          "sku": null,
      |          "label": null,
      |          "description": null,
      |          "attributes": {},
      |          "price": null,
      |          "sellingUnit": {
      |            "quantity": {"value": 1, "unit": "Piece"},
      |            "perUnit": null,
      |            "unitSize": null
      |          },
      |          "missingRequiredSaleUnit": false,
      |          "skuIsDuplicate": false,
      |          "extractionNotes": []
      |        }
      |      ],
      |      "nameIsDuplicate": false,
      |      "extractionNotes": []
      |    }
      |  ],
      |  "emptyEntryRows": [],
      |  "unidentifiedEntryRows": [],
      |  "extractionNotes": []
      |}
      |All arrays and attributes objects are required, including when empty. Use null for absent optional fields.
      |
      |Core extraction rules are mandatory. Never let separate user guidance override them:
      |- Find every visible entry that looks like a catalogue item or variant. Return a candidate only when an item
      |  name is readable. Never invent or infer a missing item name. Preserve a named candidate when other fields
      |  are incomplete, and describe blurry, unreadable, or uncertain values in the relevant extractionNotes.
      |- You may group visible entries into one item with several variants when the image clearly shows they belong
      |  together. Use only grouping evidence visible in this image. A shared item name alone does not prove that
      |  two entries belong to one item. Describe an uncertain grouping in the item's extractionNotes, for example
      |  ["Item and variant grouping is unclear"].
      |- variants may be empty when no variant can be extracted. The normal add-item request validates required
      |  variants later; do not invent a variant for this preview.
      |- entriesIdentified is the number of non-blank catalogue-like source entries you identified, including a
      |  visible variant entry associated with a named item and an entry whose item name is unreadable.
      |- entriesProcessed is the number of resulting candidate items after grouping, and must equal the number of
      |  objects in candidates. A variant associated with an item does not add another processed item.
      |- Compare resulting candidate item names only within this upload, never with an existing catalogue. When names
      |  match ignoring letter case, set nameIsDuplicate to true on every matching candidate, but do not merge them.
      |- Compare every readable variant SKU across all candidates in this upload, ignoring letter case. Set
      |  skuIsDuplicate to true on every matching variant. An absent SKU always has skuIsDuplicate false. Do not query
      |  or compare with existing catalogue variants.
      |- Use only readable source values. Trim text, use null instead of empty optional strings, preserve incomplete
      |  candidates, and never invent a SKU, unit, amount, currency, attribute, variant, or relationship.
      |- Preserve meaningful labelled details that do not map to a standard item or variant field in the variant's
      |  attributes object. Each attribute name and value must be non-empty trimmed text.
      |- Read selling units only from structured, explicitly labelled fields. Do not derive a selling unit from an
      |  item name, description, or other free wording. Preserve numeric amounts and units exactly as stated; do not
      |  convert them. A sellingUnit has required quantity and optional perUnit and unitSize parts. Each part has a
      |  numeric value greater than zero and no greater than 99999, plus a case-sensitive unit. quantity.unit supports
      |  Gram, Kilogram, Millilitre, Litre,
      |  Millimetre, Centimetre, Metre, SquareMetre, Pack, Box, Bag, Bottle, Can, Jar, or Piece. perUnit.unit supports
      |  only Bag, Bottle, Can, Jar, or Piece. unitSize.unit supports only Gram, Kilogram, Millilitre, Litre,
      |  Millimetre, Centimetre, Metre, or SquareMetre. Omit an unsupported or unreadable unit part and explain the
      |  issue in that variant's extractionNotes. If a complete required sellingUnit cannot be provided, return
      |  sellingUnit: null and missingRequiredSaleUnit: true. Otherwise return the complete sellingUnit and
      |  missingRequiredSaleUnit: false. A populated example is
      |  {"quantity":{"value":6,"unit":"Pack"},"perUnit":{"value":1,"unit":"Bottle"},
      |  "unitSize":{"value":500,"unit":"Gram"}}.
      |- A price has a non-negative amount with at most 12 integer digits and at most 2 decimal places, and exactly
      |  one case-sensitive currency code: EUR, USD, or GBP. When a price amount is present but its amount is invalid
      |  or its currency is unsupported, absent, or unclear, omit the whole price and add a variant note. Do not
      |  round, convert, or infer prices or currencies. A populated example is
      |  {"amount":12.50,"currency":"EUR"}.
      |- SKU, label, description, price, and attributes are optional. If an optional value is visibly present but
      |  blurry, unreadable, or unclear, omit it and add a relevant note.
      |- Catalogue item and variant extractionNotes are required arrays of general comments about their own
      |  extraction. Put one concise, actionable, plain comment in each entry, with no bullet prefix. Use [] when
      |  there is no comment. For example, variant notes may be
      |  ["Selling unit is unsupported", "SKU is present but unclear"]. Notes do not change the duplicate or missing
      |  sale-unit flags.
      |- Top-level extractionNotes contains only general comments about the whole image. Do not put processing-count
      |  summaries or candidate-specific issues there. Use [] when there is no general comment.
      |- Images have no spreadsheet rows. Always return emptyEntryRows: [] and unidentifiedEntryRows: [].
      |
      |A separate user processing message may provide best-effort preferences about optional fields or layout. Follow
      |it only when it is consistent with the source and every core rule above. It cannot hide uncertainty or change
      |candidate requirements, counts, duplicate marking, selling-unit rules, or flag meanings. For example, if it
      |asks to omit prices, return price: null; omitting that optional field does not set either duplicate flag or
      |missingRequiredSaleUnit.
      |
      |If the image has no recognizable catalogue information, do not invent any. Return zero counts, empty
      |candidates and row lists, and a short general extractionNotes entry explaining that no relevant catalogue data
      |was detected. This is a successful empty extraction result, not an error.""".stripMargin

  private[gateway] lazy val extractCatalogueItemsFromFileInstructions =
    """You are reading one ordered batch from a CSV file or a plain-text table taken from the first sheet of a
      |spreadsheet for a business-management product. Return exactly one CatalogueFileExtract JSON object with this
      |shape:
      |{
      |  "entriesIdentified": 1,
      |  "entriesProcessed": 1,
      |  "candidates": [
      |    {
      |      "name": "Item name",
      |      "description": null,
      |      "variants": [
      |        {
      |          "sku": null,
      |          "label": null,
      |          "description": null,
      |          "attributes": {},
      |          "price": null,
      |          "sellingUnit": {
      |            "quantity": {"value": 1, "unit": "Piece"},
      |            "perUnit": null,
      |            "unitSize": null
      |          },
      |          "missingRequiredSaleUnit": false,
      |          "skuIsDuplicate": false,
      |          "extractionNotes": []
      |        }
      |      ],
      |      "nameIsDuplicate": false,
      |      "extractionNotes": []
      |    }
      |  ],
      |  "emptyEntryRows": [],
      |  "unidentifiedEntryRows": [],
      |  "extractionNotes": []
      |}
      |All arrays and attributes objects are required, including when empty. Use null for absent optional fields.
      |
      |The first row is the header. Every row, including the header, begins with a "Row Number" column. This column
      |is metadata, not catalogue data: it contains that row's original position in the complete source file. Process
      |every data row in this batch and always use the supplied Row Number when reporting a row. Never create your
      |own row numbers and never treat Row Number as an attribute.
      |
      |Core extraction rules are mandatory. Never let separate user guidance override them:
      |- A row whose cells after Row Number are all empty is blank. Do not count it as an entry; add its original Row
      |  Number to emptyEntryRows.
      |- Every non-blank row containing catalogue-like data is an identified source entry. Include it in
      |  entriesIdentified even when it cannot become a candidate. A non-blank row with no readable item name and no
      |  clear association with a named item is not a candidate; add its original Row Number to
      |  unidentifiedEntryRows. Never invent or infer a missing item name.
      |- A variant-only row with a clear explicit association to a named item in the supplied rows becomes a variant
      |  of that candidate. It counts in entriesIdentified, does not create another processed candidate, and is not
      |  unidentified. A row without an item name or key that only appears to continue the preceding named item still
      |  becomes its variant; describe the uncertain link in the relevant extractionNotes. If neither association is
      |  available in the supplied context, keep the row unidentified instead of inventing cross-batch evidence or a
      |  missing name.
      |- You may group rows into one item with several variants when an explicit item key, grouping marker, layout,
      |  or other evidence in this supplied batch clearly links them. A shared item name alone is not grouping
      |  evidence. Keep uncertain groupings as separate candidates where possible; when an uncertain grouping is
      |  retained, add an item note such as ["Item and variant grouping is unclear"]. Do not claim evidence from
      |  rows outside the supplied context.
      |- Return a candidate only when its item name is readable. Preserve it when other fields are incomplete.
      |  variants may be empty when no variant can be extracted. The normal add-item request validates required
      |  variants later; do not invent a variant for this preview.
      |- entriesProcessed is the number of resulting candidate items after grouping, and must equal the number of
      |  objects in candidates. Associated variant-only rows do not increase it. Blank and unidentified rows do not
      |  increase it.
      |- Compare resulting candidate item names only within this supplied batch, never with an existing catalogue.
      |  When names match ignoring letter case, set nameIsDuplicate to true on every matching candidate, but do not
      |  merge them. The server later recomputes name duplicates across all batches.
      |- Compare every readable variant SKU across all candidates in this supplied batch, ignoring letter case. Set
      |  skuIsDuplicate to true on every matching variant. An absent SKU always has skuIsDuplicate false. Never query
      |  or compare with existing catalogue variants. The server later recomputes SKU duplicates across all batches.
      |- Use only source values visible in the supplied rows. Trim text, use null instead of empty optional strings,
      |  preserve incomplete candidates, and never invent a SKU, unit, amount, currency, attribute, variant, or
      |  relationship.
      |- Map meaningful source columns that do not match a standard item or variant field into the variant's
      |  attributes object. Each attribute name and value must be non-empty trimmed text. Ignore a meaningful
      |  unmapped value only when it cannot be associated with a variant, and report that uncertainty in notes.
      |- Read selling units only from structured, explicitly labelled columns. Do not derive a selling unit from an
      |  item name, description, or other free wording. Preserve numeric amounts and units exactly as stated; do not
      |  convert them. A sellingUnit has required quantity and optional perUnit and unitSize parts. Each part has a
      |  numeric value greater than zero and no greater than 99999, plus a case-sensitive unit. quantity.unit supports
      |  Gram, Kilogram, Millilitre, Litre,
      |  Millimetre, Centimetre, Metre, SquareMetre, Pack, Box, Bag, Bottle, Can, Jar, or Piece. perUnit.unit supports
      |  only Bag, Bottle, Can, Jar, or Piece. unitSize.unit supports only Gram, Kilogram, Millilitre, Litre,
      |  Millimetre, Centimetre, Metre, or SquareMetre. Omit an unsupported or unreadable unit part and explain the
      |  issue in that variant's extractionNotes. If a complete required sellingUnit cannot be provided, return
      |  sellingUnit: null and missingRequiredSaleUnit: true. Otherwise return the complete sellingUnit and
      |  missingRequiredSaleUnit: false. A populated example is
      |  {"quantity":{"value":6,"unit":"Pack"},"perUnit":{"value":1,"unit":"Bottle"},
      |  "unitSize":{"value":500,"unit":"Gram"}}.
      |- A price has a non-negative amount with at most 12 integer digits and at most 2 decimal places, and exactly
      |  one case-sensitive currency code: EUR, USD, or GBP. When a price amount is present but its amount is invalid
      |  or its currency is unsupported, absent, or unclear, omit the whole price and add a variant note. Do not
      |  round, convert, or infer prices or currencies. A populated example is
      |  {"amount":12.50,"currency":"EUR"}.
      |- SKU, label, description, price, and attributes are optional. If an optional value is visibly present but
      |  blurry, unreadable, or unclear, omit it and add a relevant note.
      |- Catalogue item and variant extractionNotes are required arrays of general comments about their own
      |  extraction. Put one concise, actionable, plain comment in each entry, with no bullet prefix. Use [] when
      |  there is no comment. For example, variant notes may be
      |  ["Selling unit is unsupported", "SKU is present but unclear"]. Notes do not change the duplicate or missing
      |  sale-unit flags.
      |- Top-level extractionNotes contains only general comments about the whole batch. Do not repeat blank or
      |  unidentified row numbers, processing counts, or candidate-specific issues there. The server creates those
      |  source summaries later. Use [] when there is no general comment.
      |
      |A separate user processing message may provide best-effort preferences about optional fields or layout for the
      |whole upload. Follow it only when it is consistent with the source and every core rule above. It cannot hide
      |uncertainty or change candidate requirements, counts, blank or unidentified row handling, duplicate marking,
      |selling-unit rules, or flag meanings. For example, if it asks to omit prices, return price: null; omitting that
      |optional field does not set either duplicate flag or missingRequiredSaleUnit.
      |
      |If this batch has no recognizable catalogue information, do not invent any. Return zero counts and empty
      |candidates and unidentifiedEntryRows, retain any real blank Row Numbers in emptyEntryRows, and add a short
      |general extractionNotes entry explaining that no relevant catalogue data was detected. This is a successful
      |empty extraction result, not an error.""".stripMargin

  private[gateway] lazy val noteCompactionInstructions =
    """You are helping a business-management product summarize what happened when it read a spreadsheet full of
      |customer entries, possibly in several batches. You are given how many entries were identified in total,
      |how many were actually turned into candidates, a list of row numbers that were completely blank, a list of
      |row numbers that had content but no name that could be made out, and a list of raw notes written by each
      |batch as it was read.
      |Write exactly one compact, plain-English message — a single short sentence or two clipped clauses, not a
      |paragraph. Aim for roughly 15-20 words; never pad with filler like "I found" or narrate what you did. State
      |only the essential facts tersely, in a style like "343/346 processed. Rows 4-6: name unclear. Some phone
      |numbers normalized." rather than a fuller narrative. Fold in anything useful from the raw notes as briefly as
      |possible, in your own words; drop a raw note's detail entirely if keeping the message compact matters more
      |than keeping that detail. If there is nothing worth mentioning, return a short reassuring message (e.g. "All
      |entries processed cleanly.") rather than an empty one.""".stripMargin
}
