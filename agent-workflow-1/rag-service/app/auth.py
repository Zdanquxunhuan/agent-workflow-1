from dataclasses import dataclass
from functools import lru_cache
import re

import jwt
from fastapi import Depends, Header

from app.config import settings
from app.db import transaction
from app.errors import RagError


@dataclass(frozen=True)
class Principal:
    tenant_id: str
    subject: str
    role: str


@lru_cache
def jwks():
    return jwt.PyJWKClient(settings().auth_jwks_url, timeout=5, lifespan=300)


def verify_token(token: str) -> tuple[str, str]:
    cfg = settings()
    try:
        signing_key = (cfg.auth_secret if cfg.auth_mode == "local"
                       else jwks().get_signing_key_from_jwt(token).key)
        claims = jwt.decode(token, signing_key,
                            algorithms=["HS256"] if cfg.auth_mode == "local" else ["RS256"],
                            audience=cfg.auth_audience, issuer=cfg.auth_issuer,
                            options={"require": ["exp", "iat", "sub", "tenant_id", "aud", "iss"]})
        tenant, subject = claims["tenant_id"], claims["sub"]
        if not isinstance(tenant, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", tenant):
            raise ValueError("invalid tenant")
        if not isinstance(subject, str) or not 1 <= len(subject) <= 200:
            raise ValueError("invalid subject")
        return tenant, subject
    except (jwt.PyJWTError, ValueError, TypeError) as exc:
        raise RagError("UNAUTHORIZED", "Valid RAG bearer token required", 401) from exc


def principal(authorization: str = Header(default="")) -> Principal:
    if not authorization.startswith("Bearer "):
        raise RagError("UNAUTHORIZED", "Authentication required", 401)
    tenant, subject = verify_token(authorization[7:])
    with transaction(tenant) as conn:
        row = conn.execute("SELECT role FROM tenant_memberships WHERE tenant_id=%s AND subject=%s "
                           "AND enabled", (tenant, subject)).fetchone()
    if not row:
        raise RagError("FORBIDDEN", "Active tenant membership required", 403)
    return Principal(tenant, subject, row["role"])


def require_kb(conn, user: Principal, kb_id, write=False, manage=False):
    member = conn.execute("SELECT role FROM tenant_memberships WHERE tenant_id=%s AND subject=%s AND enabled",
                          (user.tenant_id, user.subject)).fetchone()
    if not member:
        raise RagError("FORBIDDEN", "Active tenant membership required", 403)
    role = member["role"]
    row = conn.execute("""SELECT k.*, a.role AS kb_role FROM knowledge_bases k
        LEFT JOIN kb_acl a ON a.tenant_id=k.tenant_id AND a.kb_id=k.id AND a.subject=%s
        WHERE k.tenant_id=%s AND k.id=%s""", (user.subject, user.tenant_id, kb_id)).fetchone()
    if not row or (role != "admin" and row["kb_role"] is None):
        raise RagError("NOT_FOUND", "Knowledge base not found", 404)
    if role != "admin":
        allowed = {"manager"} if manage else {"manager", "contributor"} if write else {"manager", "contributor", "reader"}
        if row["kb_role"] not in allowed:
            raise RagError("FORBIDDEN", "Operation is not permitted", 403)
    return row


def document_visible(conn, user: Principal, doc_id, manage=False):
    row = conn.execute("SELECT * FROM documents WHERE tenant_id=%s AND id=%s AND deleted_at IS NULL",
                       (user.tenant_id, doc_id)).fetchone()
    if not row:
        raise RagError("NOT_FOUND", "Document not found", 404)
    require_kb(conn, user, row["kb_id"], manage=manage)
    if not manage and row["allowed_subjects"] is not None and user.subject not in row["allowed_subjects"]:
        raise RagError("NOT_FOUND", "Document not found", 404)
    return row

