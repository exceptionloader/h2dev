"""Outgoing e-mail via SMTP. Without NOVA_SMTP_HOST, messages are only logged (dev/test)."""

import asyncio
import logging
import smtplib
import ssl
from email.message import EmailMessage

from app.core.config import get_settings

log = logging.getLogger("nova.mail")


def _send_smtp(msg: EmailMessage) -> None:
    s = get_settings()
    assert s.smtp_host
    context = ssl.create_default_context()
    if s.smtp_security == "ssl":
        server: smtplib.SMTP = smtplib.SMTP_SSL(
            s.smtp_host, s.smtp_port, context=context, timeout=15
        )
    else:
        server = smtplib.SMTP(s.smtp_host, s.smtp_port, timeout=15)
    with server:
        if s.smtp_security == "starttls":
            server.starttls(context=context)
        if s.smtp_username and s.smtp_password:
            server.login(s.smtp_username, s.smtp_password)
        server.send_message(msg)


async def send(to: str, subject: str, body: str) -> None:
    settings = get_settings()
    if not settings.smtp_host:
        log.info("mail.outgoing", extra={"to": to, "subject": subject, "body": body})
        return
    msg = EmailMessage()
    msg["From"] = settings.mail_from
    msg["To"] = to
    msg["Subject"] = subject
    msg.set_content(body)
    try:
        await asyncio.to_thread(_send_smtp, msg)
        log.info("mail.sent", extra={"to": to, "subject": subject})
    except (OSError, smtplib.SMTPException):
        # Never leak delivery problems to the requester (password reset must not reveal
        # anything); the operator sees the error in the logs.
        log.exception("mail.failed", extra={"to": to, "subject": subject})
