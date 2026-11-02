import io
import math
import time
import zipfile

import jwt
import pytest

from app.auth import verify_token
from app.config import Settings, settings
from app.db import vector_literal
from app.errors import RagError
from app.parsing import parse, validate_file


def test_text_parser_preserves_source_and_rejects_empty():
    assert parse("policy.md", "退款需要审批。".encode())[0]["text"] == "退款需要审批。"
    with pytest.raises(RagError, match="No extractable text"):
        parse("empty.txt", b"   ")


def test_file_type_and_encoding_are_not_trusted():
    for name, content in [("fake.pdf", b"hello"), ("fake.docx", b"hello"),
                          ("program.exe", b"hello"), ("binary.txt", b"\x00hello"),
                          ("bad.txt", b"\xff\xfe")]:
        with pytest.raises(RagError):
            validate_file(name, content)


def test_docx_archive_expansion_is_bounded():
    content = io.BytesIO()
    with zipfile.ZipFile(content, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("word/document.xml", b"a" * (51 * 1024 * 1024))
    with pytest.raises(RagError, match="expansion"):
        validate_file("bomb.docx", content.getvalue())


def test_vector_validation_rejects_invalid_or_zero_vectors():
    for value in [[1.0] * 3, [0.0] * 1024, [math.nan] * 1024, [math.inf] * 1024]:
        with pytest.raises(ValueError):
            vector_literal(value)
    assert vector_literal([1.0] + [0.0] * 1023).startswith("[1.0,")


def test_production_cannot_enable_local_auth_or_development_credentials():
    with pytest.raises(ValueError, match="production requires OIDC"):
        Settings(environment="production")
    with pytest.raises(ValueError, match="development credentials"):
        Settings(environment="production", auth_mode="oidc", auth_jwks_url="https://identity.example/jwks",
                 dev_login_enabled=False)


def test_jwt_audience_expiry_and_tenant_are_checked():
    cfg = settings()
    claims = {"sub": "alice", "tenant_id": "demo", "iss": cfg.auth_issuer,
              "aud": cfg.auth_audience, "iat": int(time.time()), "exp": int(time.time()) + 60}
    assert verify_token(jwt.encode(claims, cfg.auth_secret, algorithm="HS256")) == ("demo", "alice")
    for override in [{"aud": "another-app"}, {"exp": 1}, {"tenant_id": ""}, {"iss": "untrusted"}]:
        with pytest.raises(RagError):
            verify_token(jwt.encode(claims | override, cfg.auth_secret, algorithm="HS256"))

