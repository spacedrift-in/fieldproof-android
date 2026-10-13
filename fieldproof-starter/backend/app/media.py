"""Content-based media type validation.

We never trust the client-supplied ``Content-Type`` or filename. Instead the
leading bytes of an uploaded file are inspected for a known image signature and
the canonical MIME type is derived from that. This is deliberately dependency
free (no ``python-magic``/libmagic) so it runs identically in local dev, CI and
the production container. The allowlist of accepted types is configurable.
"""
from __future__ import annotations

from fastapi import HTTPException


def _is_jpeg(head: bytes) -> bool:
    return head.startswith(b"\xff\xd8\xff")


def _is_png(head: bytes) -> bool:
    return head.startswith(b"\x89PNG\r\n\x1a\n")


def _is_gif(head: bytes) -> bool:
    return head.startswith(b"GIF87a") or head.startswith(b"GIF89a")


def _is_webp(head: bytes) -> bool:
    return len(head) >= 12 and head[0:4] == b"RIFF" and head[8:12] == b"WEBP"


_HEIF_BRANDS = {
    b"heic", b"heix", b"hevc", b"hevx",
    b"heim", b"heis", b"hevm", b"hevs",
    b"heif", b"mif1", b"msf1",
}


def _is_heif(head: bytes) -> bool:
    return len(head) >= 12 and head[4:8] == b"ftyp" and head[8:12] in _HEIF_BRANDS


# Ordered so more specific checks run before broader ones.
_SIGNATURES = (
    ("image/png", _is_png),
    ("image/jpeg", _is_jpeg),
    ("image/gif", _is_gif),
    ("image/webp", _is_webp),
    ("image/heic", _is_heif),
)

# Aliases a client may legitimately send that normalise to a canonical type.
_DECLARED_ALIASES = {
    "image/jpg": "image/jpeg",
    "image/pjpeg": "image/jpeg",
    "image/x-png": "image/png",
    "image/heif": "image/heic",
}


def sniff_image_type(head: bytes) -> str | None:
    """Return the canonical image MIME type for ``head`` bytes, or ``None``."""
    for mime, predicate in _SIGNATURES:
        if predicate(head):
            return mime
    return None


def validate_image_upload(
    content: bytes,
    *,
    declared_content_type: str | None,
    allowed: tuple[str, ...],
) -> str:
    """Validate an uploaded image and return its canonical MIME type.

    Raises :class:`fastapi.HTTPException` (415) when the bytes are not a
    recognised image, when the type is not allowlisted, or when the declared
    type contradicts the detected content. Declared types such as
    ``application/octet-stream`` are ignored in favour of the sniffed type.
    """
    if not content:
        raise HTTPException(status_code=422, detail="A photo file is required for kind='photo'")

    detected = sniff_image_type(content[:64])
    if detected is None:
        raise HTTPException(
            status_code=415,
            detail="Unsupported media type: the uploaded file is not a recognised image.",
        )

    allowed_set = {t.lower() for t in allowed}
    if detected not in allowed_set:
        raise HTTPException(
            status_code=415,
            detail=f"Unsupported image type {detected}; allowed types: {sorted(allowed_set)}.",
        )

    declared_normalised = None
    if declared_content_type:
        declared_normalised = _DECLARED_ALIASES.get(
            declared_content_type.split(";")[0].strip().lower(),
            declared_content_type.split(";")[0].strip().lower(),
        )
    if declared_normalised and declared_normalised.startswith("image/"):
        if declared_normalised != detected:
            raise HTTPException(
                status_code=415,
                detail=(
                    f"Declared content type {declared_normalised} does not match the "
                    f"detected image type {detected}."
                ),
            )

    return detected
