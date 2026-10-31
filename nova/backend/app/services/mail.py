"""Outgoing e-mail.

The MVP has no SMTP provider yet: in development the message is written to the log so
the reset link can be copied from the backend output. Swap ``send`` for a real provider
before inviting beta testers who need password resets.
"""

import logging

log = logging.getLogger("nova.mail")


async def send(to: str, subject: str, body: str) -> None:
    log.info("mail.outgoing", extra={"to": to, "subject": subject, "body": body})
