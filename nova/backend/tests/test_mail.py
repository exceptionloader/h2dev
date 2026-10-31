import smtplib

from app.core.config import get_settings
from app.services import mail


class FakeSMTP:
    sent: list = []
    calls: list = []

    def __init__(self, host, port, timeout=None, **kwargs):
        FakeSMTP.calls.append(("connect", host, port))

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def starttls(self, context=None):
        FakeSMTP.calls.append(("starttls",))

    def login(self, user, password):
        FakeSMTP.calls.append(("login", user))

    def send_message(self, msg):
        FakeSMTP.sent.append(msg)


async def test_smtp_delivery(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "smtp_host", "smtp.example.com")
    monkeypatch.setattr(s, "smtp_username", "nova")
    monkeypatch.setattr(s, "smtp_password", "pw")
    monkeypatch.setattr(smtplib, "SMTP", FakeSMTP)
    await mail.send("jakob@example.com", "Betreff", "Hallo")
    msg = FakeSMTP.sent[-1]
    assert msg["To"] == "jakob@example.com" and msg["Subject"] == "Betreff"
    assert ("starttls",) in FakeSMTP.calls and ("login", "nova") in FakeSMTP.calls


async def test_smtp_failure_is_swallowed(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "smtp_host", "smtp.example.com")

    def boom(*a, **k):
        raise OSError("down")

    monkeypatch.setattr(smtplib, "SMTP", boom)
    await mail.send("x@example.com", "s", "b")  # must not raise (no account enumeration)
