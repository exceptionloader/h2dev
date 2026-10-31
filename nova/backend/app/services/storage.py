"""File storage for uploads (local directory or S3-compatible) plus upload validation."""

import asyncio
import re
import uuid
from functools import lru_cache
from pathlib import Path
from typing import Protocol

from fastapi import UploadFile

from app.core.config import get_settings
from app.core.errors import AppError

_KEY_RE = re.compile(r"^[a-z]+/[0-9a-f-]{36}(\.[a-z0-9]{1,5})?$")

IMAGE_TYPES = {
    "image/png": "png",
    "image/jpeg": "jpg",
    "image/gif": "gif",
    "image/webp": "webp",
}


def sniff_image(data: bytes) -> str | None:
    """Detect the image type from magic bytes — never trust the client's content type."""
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def safe_filename(name: str | None) -> str:
    name = (name or "datei").replace("\\", "/").rsplit("/", 1)[-1]
    name = re.sub(r"[^\w.\- ]+", "_", name, flags=re.UNICODE).strip(" .") or "datei"
    return name[:120]


async def read_limited(upload: UploadFile, limit: int) -> bytes:
    chunks: list[bytes] = []
    size = 0
    while chunk := await upload.read(64 * 1024):
        size += len(chunk)
        if size > limit:
            raise AppError(f"File larger than {limit} bytes", code="file_too_large")
        chunks.append(chunk)
    if size == 0:
        raise AppError("Empty file", code="file_empty")
    return b"".join(chunks)


def new_key(prefix: str, ext: str | None) -> str:
    return f"{prefix}/{uuid.uuid4()}" + (f".{ext}" if ext else "")


def valid_key(key: str) -> bool:
    return bool(_KEY_RE.fullmatch(key))


class Storage(Protocol):
    async def save(self, key: str, data: bytes, content_type: str) -> None: ...
    async def load(self, key: str) -> bytes | None: ...
    async def delete(self, key: str) -> None: ...


class LocalStorage:
    def __init__(self, root: str) -> None:
        self.root = Path(root).resolve()

    def _path(self, key: str) -> Path:
        if not valid_key(key):
            raise ValueError("invalid storage key")
        return self.root / key

    async def save(self, key: str, data: bytes, content_type: str) -> None:
        path = self._path(key)

        def write() -> None:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

        await asyncio.to_thread(write)

    async def load(self, key: str) -> bytes | None:
        path = self._path(key)
        return await asyncio.to_thread(lambda: path.read_bytes() if path.is_file() else None)

    async def delete(self, key: str) -> None:
        path = self._path(key)
        await asyncio.to_thread(lambda: path.unlink(missing_ok=True))


class S3Storage:
    def __init__(self) -> None:
        import boto3

        s = get_settings()
        self.bucket = s.s3_bucket
        self.client = boto3.client(
            "s3",
            endpoint_url=s.s3_endpoint_url,
            region_name=s.s3_region,
            aws_access_key_id=s.s3_access_key,
            aws_secret_access_key=s.s3_secret_key,
        )

    async def save(self, key: str, data: bytes, content_type: str) -> None:
        await asyncio.to_thread(
            self.client.put_object, Bucket=self.bucket, Key=key, Body=data, ContentType=content_type
        )

    async def load(self, key: str) -> bytes | None:
        def get() -> bytes | None:
            try:
                return self.client.get_object(Bucket=self.bucket, Key=key)["Body"].read()
            except self.client.exceptions.NoSuchKey:
                return None

        return await asyncio.to_thread(get)

    async def delete(self, key: str) -> None:
        await asyncio.to_thread(self.client.delete_object, Bucket=self.bucket, Key=key)


@lru_cache
def get_storage() -> Storage:
    s = get_settings()
    if s.storage_backend == "s3":
        return S3Storage()
    return LocalStorage(s.storage_local_path)
