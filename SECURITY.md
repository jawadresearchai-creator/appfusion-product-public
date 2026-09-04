# Security and privacy

Never commit user documents, input application packages, decompiled material,
private source repositories, private storage identifiers, service credentials,
production secrets or signing keys. Test fixtures must be synthetic.

Run `python scripts/verify_publication.py` before publishing changes. Its pattern
checks supplement human review and are not a guarantee that all sensitive data
or security defects have been discovered.

Use GitHub private vulnerability reporting when enabled. Do not disclose private
evidence or exploit details in a public issue. This prototype has not completed
the final security audit and must not hold irreplaceable data.

CI uses standard public runners, read-only repository tokens, no repository
secrets, no private-source checkout and bounded execution. No paid overage or
billing activation is permitted. Workflow files are part of the reviewed source.
