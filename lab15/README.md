# Lab 15: personal data and erasure

An event store is designed to retain facts. Data-protection law may require personal data to be erased. Under GDPR Article 17 that right applies on specified grounds and has explicit exceptions; it is not an unconditional instruction to delete every historical fact. This lab is an architectural exercise in minimisation and making selected fields unrecoverable, not legal advice or proof of compliance. ([GDPR Article 17](https://eur-lex.europa.eu/eli/reg/2016/679/art_17/oj))

## Why routine row deletion breaks this store

Not because immutability is elegant — because deleting one row breaks invariants earlier labs assert in tests.

[Lab7](../lab7) pins stream versions as contiguous `1..n`. Take an event out of the middle and there is a hole:

```clojure
[1 3 4]
```

Replay produces a history different from the one originally recorded. The audit trail is altered, and optimistic-concurrency code now has a gap to explain. By then the fact may also have been projected, published ([lab12](../lab12)) and backed up, so the row may be only one copy.

A test shows this breakage directly, so the alternative has something concrete to be better than.

This is a design choice, not a law of event stores. A system may need controlled redaction, stream deletion or physical migration to satisfy its actual obligations. As [lab13](../lab13) explains, that requires retained originals where lawful, provenance, verification and deliberate reader changes. Deleting one row and pretending the surrounding history is untouched is the unsafe version.

## Two answers, and minimisation is the default

**1. Minimise personal data in the log.** Events reference a subject by an opaque id; direct identifying attributes live in an ordinary mutable store with an appropriate deletion lifecycle.

A sale carries:

```clojure
{:event/type :flavour-sold
 :data       {:flavour "vanilla" :customer-id "C-123"}}
```

A test pins those data keys at exactly `#{:flavour :customer-id}` — no repeated name or email. That is data minimisation, not anonymisation. If the controller can reconnect `"C-123"` to a person, the identifier and linked sales remain pseudonymous personal data and stay within data-protection scope. ([ICO pseudonymisation guidance](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/data-sharing/anonymisation/pseudonymisation/))

**2. Crypto-shredding**, for a deliberately justified residue that cannot be separated without losing required historical meaning. This lab assumes the exact personal attributes captured at card issuance must be retained with that fact. In a real design, challenge that requirement first: if a subject reference is sufficient, keep the attributes out of the event instead. Here the residual field is encrypted under a non-replaceable key belonging to that subject, and the after-erasure vault state no longer exposes that key.

Exactly one event in the log holds such a protected field: the card issuance. That ratio is the point. Minimisation does the heavy lifting; shredding handles the justified residue.

## Protection is an edge concern in both directions

The pure domain proposes a `:card-issued` event containing the business value. Before append, the application protection edge replaces that field with ciphertext. The store never receives plaintext and does not know how encryption works; the domain never receives a key and does not depend on cryptographic technology.

On read, the inverse edge produces either plaintext or an explicit marker:

```clojure
(read-event vault-with-key    card-event)  ;; => {:name "Aoife Ní Bhriain" …}
(read-event vault-without-key card-event)  ;; => :personal/erased
```

That marker is [lab13](../lab13)'s `:price/unknown` in a different costume: not `nil` and not `""`. Both are values the field could legitimately have held, so both could let erasure quietly pass for data. An explicit marker forces every reader to decide what it means.

The encrypted envelope carries a format version and algorithm. AES-GCM additional authenticated data binds the ciphertext to the card-personal field, subject id and immutable event id, so moving it to another subject or fact fails authentication. Missing key material means `:personal/erased`; a wrong key, altered ciphertext, unknown encryption version or unknown event type is an error, not evidence of erasure. That is [lab13](../lab13)'s strict compatibility boundary applied to protected data.

## Direct identifying attributes go; retained facts stay

```clojure
(replay-truck stream)                     ;; => {"vanilla" 7}
(replay-truck (read-all shredded stream)) ;; => {"vanilla" 7}
```

Three cones were sold before the key became unavailable, and they still were. The card is still known to have been issued to pseudonym `C-123`. The name and email cannot be recovered through the after-erasure vault state, while accounting and stock reconciliation still use the retained facts.

Whether `C-123` remains linkable to a person through another system is a separate inventory and governance question. Crypto-shredding one field does not make the event log anonymous.

The modelling rule is: **protect the smallest justified subset of `:data`, not the whole fact.**

## Where erasure leaks

Making the key unavailable makes the protected event field unreadable through this reader. It does nothing to a projection that already materialised the plaintext — that is a separate copy made while the key existed.

```clojure
(name-of (rebuild held    log) "C-123")  ;; => "Aoife Ní Bhriain"
(name-of (rebuild shredded log) "C-123") ;; => :personal/erased
```

The operational workflow is therefore larger than one key-store call: verify key destruction, then delete, rebuild or otherwise purge every projection, cache, index and log that materialised the plaintext. [Lab9](../lab9) argued that read models should be reproducible; here that property makes rebuilding one practical purge strategy. A non-rebuildable projection still needs its own supported erasure mechanism.

Once an integration message has left ([lab12](../lab12)), another module or organisation may hold its own copy. The sender cannot revoke bytes already delivered; it needs an erasure or correction contract and an organisational process. [GDPR Article 19](https://eur-lex.europa.eu/eli/reg/2016/679/art_19/oj) can require a controller to communicate erasure to recipients unless that is impossible or involves disproportionate effort. Publication is [lab14](../lab14)'s pivot at the technical level, not an excuse to stop coordinating downstream deletion.

## What this demonstration does not solve

**Key management has a destructive lifecycle.** Crypto-shredding needs something that can make key material irrecoverable. This lab models before and after as immutable vault values, so retaining the before-value deliberately retains the key; `destroy` does not sanitise JVM memory. A production KMS adapter must perform and verify the destructive operation. The tombstone in the after-state prevents accidentally assigning a new key to the same subject id and making old ciphertext look merely corrupt.

**Backups and escrow.** Any backup or escrow containing a recoverable key defeats the claimed destruction until that copy is handled. NIST SP 800-88 Rev. 2 says cryptographic erase should not be trusted for backed-up or escrowed keys without high confidence in how those copies are managed, and requires a separate sanitisation policy for them. ([NIST SP 800-88 Rev. 2](https://www.nist.gov/publications/guidelines-media-sanitization-3))

**Cryptographic lifecycle.** A 96-bit random GCM IV is appropriate for this small demonstration, but nonce uniqueness under one key is critical. A production design also needs algorithm agility, key rotation or an explicit no-rotation policy, access controls, audit, failure handling and assurance that every key copy was destroyed. The versioned envelope lets this reader reject an unsupported format; it does not implement those operational controls.

**Plaintext before protection.** The command and domain proposal necessarily hold the personal value before the application seals it. Transport, traces, exception reporting and application logs must not copy that value. Encryption at the event-store edge does not clean up plaintext leaked earlier in the request path.

**Legal compliance.** Article 17 contains grounds for erasure and exceptions including legal obligations, public-interest tasks, certain archiving or research uses, and legal claims. The architecture has to be validated against lawful basis, retention schedules, audit duties, backups, recipients, projections, logs and reporting.

## What survives on purpose

Erasure is not automatically all-or-nothing. Some processing may need to stop while particular records remain under a documented lawful basis or Article 17 exception.

The design question is not merely "how do I delete a customer". It is **which data relates to an identifiable person, why each field is processed, how long each copy is retained, and which supported mechanism removes or restricts it**. Truly destroyed key material cannot be recovered, so retention and erasure decisions need explicit authority rather than an ad hoc button.

## Testing the boundaries

Pure domain tests exercise card and truck business behavior without keys or cryptographic dependencies. Reader and vault tests use real AES-GCM to prove round trips, randomised ciphertext, authenticated context, strict envelope versions and failure with the wrong key. Application behavior tests enter through the write use case and assert that plaintext never crosses the store boundary, identity and causation are preserved, erasure changes only the protected interpretation, and unknown semantics fail.

A production KMS and database require focused adapter tests against the real services. This in-memory value model cannot prove key sanitisation.

## What's next

The write side, read side, publication, evolution, failure and erasure are now covered. What has never been asked is where the aggregate boundary goes — which events belong in one stream at all. [Lab16](../lab16) builds one domain three ways and measures the difference.

## Running it

```bash
bb all      # setup, check, test
bb test     # just the tests
```
