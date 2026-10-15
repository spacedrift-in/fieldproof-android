"""SQLAlchemy ORM models.

PostgreSQL is the demo source of truth. Tables are created with
``Base.metadata.create_all`` for the demo; production would use migrations.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    JSON,
    String,
    UniqueConstraint,
    Uuid,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Inspection(Base):
    __tablename__ = "inspections"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    tenant_id: Mapped[str] = mapped_column(
        String(63), nullable=False, default="demo", server_default="demo", index=True
    )
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    location: Mapped[str] = mapped_column(String(200), nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="DRAFT")
    version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    template_id: Mapped[uuid.UUID | None] = mapped_column(Uuid(as_uuid=True), nullable=True)
    # Epoch milliseconds supplied by the (offline) client.
    client_created_at: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow
    )

    checklist_items: Mapped[list["ChecklistItem"]] = relationship(
        back_populates="inspection",
        cascade="all, delete-orphan",
        order_by="ChecklistItem.sort_order",
    )
    evidence: Mapped[list["Evidence"]] = relationship(
        back_populates="inspection",
        cascade="all, delete-orphan",
    )
    reviews: Mapped[list["Review"]] = relationship(
        back_populates="inspection",
        cascade="all, delete-orphan",
        order_by="Review.reviewed_at",
    )


class ChecklistItem(Base):
    __tablename__ = "checklist_items"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    inspection_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("inspections.id", ondelete="CASCADE"), index=True
    )
    template_item_id: Mapped[uuid.UUID | None] = mapped_column(Uuid(as_uuid=True), nullable=True)
    label: Mapped[str] = mapped_column(String(300), nullable=False)
    required: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    evidence_requirement: Mapped[str] = mapped_column(String(32), nullable=False, default="none")
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="NOT_STARTED")
    note: Mapped[str | None] = mapped_column(String(2000), nullable=True)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)

    inspection: Mapped["Inspection"] = relationship(back_populates="checklist_items")
    evidence: Mapped[list["Evidence"]] = relationship(back_populates="checklist_item")


class Evidence(Base):
    __tablename__ = "evidence"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    tenant_id: Mapped[str] = mapped_column(
        String(63), nullable=False, default="demo", server_default="demo", index=True
    )
    inspection_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("inspections.id", ondelete="CASCADE"), index=True
    )
    checklist_item_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("checklist_items.id", ondelete="SET NULL"), nullable=True
    )
    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    # Text body for ``kind == "note"`` evidence; null for photos.
    note: Mapped[str | None] = mapped_column(String(2000), nullable=True)
    # Server-side storage reference only. Client local paths are never accepted.
    storage_key: Mapped[str | None] = mapped_column(String(500), nullable=True)
    sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    content_type: Mapped[str | None] = mapped_column(String(100), nullable=True)
    size_bytes: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    original_filename: Mapped[str | None] = mapped_column(String(300), nullable=True)
    captured_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow
    )
    uploaded_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow
    )

    inspection: Mapped["Inspection"] = relationship(back_populates="evidence")
    checklist_item: Mapped["ChecklistItem | None"] = relationship(back_populates="evidence")


class Review(Base):
    __tablename__ = "reviews"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    inspection_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("inspections.id", ondelete="CASCADE"), index=True
    )
    reviewer_id: Mapped[str] = mapped_column(String(200), nullable=False, default="demo-reviewer")
    decision: Mapped[str] = mapped_column(String(32), nullable=False)
    comment: Mapped[str | None] = mapped_column(String(2000), nullable=True)
    inspection_version: Mapped[int] = mapped_column(Integer, nullable=False)
    reviewed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow
    )

    inspection: Mapped["Inspection"] = relationship(back_populates="reviews")


class IdempotencyRecord(Base):
    __tablename__ = "idempotency_records"
    __table_args__ = (UniqueConstraint("key", "scope", name="uq_idempotency_key_scope"),)

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    key: Mapped[str] = mapped_column(String(200), nullable=False, index=True)
    scope: Mapped[str] = mapped_column(String(200), nullable=False)
    fingerprint: Mapped[str] = mapped_column(String(64), nullable=False)
    entity_type: Mapped[str] = mapped_column(String(50), nullable=False)
    entity_id: Mapped[str] = mapped_column(String(100), nullable=False)
    response_status: Mapped[int] = mapped_column(Integer, nullable=False)
    response_body: Mapped[dict] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow
    )
