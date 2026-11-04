"""One read-only MCP service, bound to the same single-tenant commerce ledger as the agent."""
import asyncio
import contextlib
import json
import logging
import os
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

import jwt
import pymysql
from pydantic import AnyHttpUrl
from jsonschema import validate
from mcp import types
from mcp.server.lowlevel import Server
from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
from mcp.server.transport_security import TransportSecuritySettings
from mcp.server.auth.provider import AccessToken
from mcp.server.auth.middleware.bearer_auth import BearerAuthBackend, RequireAuthMiddleware
from mcp.server.auth.middleware.auth_context import AuthContextMiddleware, get_access_token
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.authentication import AuthenticationMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

log = logging.getLogger('shipment-mcp')
PROTOCOL = '2025-11-25'
contract_path = Path(os.environ.get('SHIPMENT_CONTRACT_PATH', str(Path(__file__).resolve().parents[1] / 'src/main/resources/mcp/shipment-v1.json')))
CONTRACT = json.loads(contract_path.read_text(encoding='utf-8'))


class Config:
    def __init__(self):
        environment = os.environ.get('SHIPMENT_ENVIRONMENT', 'production')
        if environment not in {'production', 'development'}:
            raise ValueError('Unknown deployment environment')
        self.production = environment == 'production'
        self.mode = os.environ.get('SHIPMENT_AUTH_MODE', 'oidc')
        self.resource = os.environ['SHIPMENT_RESOURCE_URL']
        self.issuer = os.environ['SHIPMENT_ISSUER']
        self.jwks = os.environ.get('SHIPMENT_JWKS_URL', '')
        self.client = os.environ['SHIPMENT_ALLOWED_CLIENT']
        self.secret = os.environ.get('SHIPMENT_LOCAL_SECRET', '')
        self.tenant = os.environ['SHIPMENT_TENANT']
        self.ca = os.environ.get('SHIPMENT_DB_CA', '')
        self.hosts = [h.strip() for h in os.environ['SHIPMENT_ALLOWED_HOSTS'].split(',')]
        self.origins = [h.strip() for h in os.environ.get('SHIPMENT_ALLOWED_ORIGINS', '').split(',')]
        if self.mode not in {'local', 'oidc'} or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', self.tenant):
            raise ValueError('Invalid MCP identity configuration')
        resource = urlsplit(self.resource)
        if resource.path != '/mcp' or resource.query or resource.fragment or resource.username is not None or not resource.hostname:
            raise ValueError('Resource URL must be the configured /mcp endpoint')
        for value in [self.resource, self.issuer] + ([self.jwks] if self.mode == 'oidc' else []):
            url = urlsplit(value)
            if not url.hostname or url.username is not None or url.fragment or url.query:
                raise ValueError('Invalid identity URL')
            if url.scheme != 'https' and (self.production or url.scheme != 'http' or url.hostname not in {'localhost', '127.0.0.1', '::1'}):
                raise ValueError('HTTPS required outside local loopback development')
        if self.production and (self.mode != 'oidc' or not self.ca):
            raise ValueError('Production requires OIDC and verified MySQL TLS')
        if self.mode == 'local' and len(self.secret) < 32:
            raise ValueError('Local mode requires a dedicated development JWT secret')
        if not self.client or not all(h and '*' not in h for h in self.hosts) or any('*' in o for o in self.origins):
            raise ValueError('Explicit Host allowlist required')
        self.db = dict(host=os.environ['SHIPMENT_DB_HOST'], port=int(os.environ.get('SHIPMENT_DB_PORT', '3306')),
                       user=os.environ['SHIPMENT_DB_USER'], password=os.environ['SHIPMENT_DB_PASSWORD'],
                       database=os.environ['SHIPMENT_DB_NAME'], charset='utf8mb4', autocommit=True,
                       cursorclass=pymysql.cursors.DictCursor, connect_timeout=2, read_timeout=3, write_timeout=3)
        if self.db['user'].lower() in {'root', 'workflow'}:
            raise ValueError('Use a separate least-privilege MCP database account')
        if self.production and not self.db['password']:
            raise ValueError('Production database credential required')
        if self.ca:
            self.db.update(ssl_ca=self.ca, ssl_verify_cert=True, ssl_verify_identity=True)


cfg = Config()
jwks = jwt.PyJWKClient(cfg.jwks, timeout=3, lifespan=300) if cfg.mode == 'oidc' else None


class Verifier:
    async def verify_token(self, token):
        if len(token) > 8192:
            return None
        def decode():
            key = cfg.secret if cfg.mode == 'local' else jwks.get_signing_key_from_jwt(token).key
            return jwt.decode(token, key, algorithms=['HS256'] if cfg.mode == 'local' else ['RS256'],
                              issuer=cfg.issuer, audience=cfg.resource,
                              options={'require': ['exp', 'iat', 'sub', 'tenant_id']})
        try:
            claims = await asyncio.to_thread(decode)
            subject = claims['sub']
            scopes = claims.get('scope', 'shipment:read' if cfg.mode == 'local' else '').split()
            client = claims.get('client_id', claims.get('azp', cfg.client if cfg.mode == 'local' else ''))
            if (claims['tenant_id'] != cfg.tenant or client != cfg.client or 'shipment:read' not in scopes
                    or not isinstance(subject, str) or not re.fullmatch(r'[A-Za-z0-9._@-]{1,200}', subject)):
                return None
            return AccessToken(token=token, client_id=client, subject=subject, resource=cfg.resource,
                               scopes=scopes, expires_at=int(claims['exp']), claims={'iss': cfg.issuer, 'tenant_id': cfg.tenant})
        except (jwt.PyJWTError, ValueError, TypeError, AttributeError, KeyError):
            return None


class Denied(Exception):
    def __init__(self, code): self.code = code


def connection():
    conn = pymysql.connect(**cfg.db)
    try:
        with conn.cursor() as cur:
            cur.execute("SET time_zone='+00:00'")
            cur.execute('SET SESSION innodb_lock_wait_timeout=2')
    except Exception:
        conn.close()
        raise
    return conn


def grant(cur, subject, order):
    cur.execute('SELECT revision FROM mcp_order_grants WHERE tenant_id=%s AND subject_id=%s AND order_id=%s '
                'AND enabled=true AND expires_at>UNIX_TIMESTAMP()', (cfg.tenant, subject, order))
    row = cur.fetchone()
    if not row: raise Denied('MCP_ACCESS_DENIED')
    return row['revision']


def admission(subject):
    with connection() as conn, conn.cursor() as cur:
        conn.begin()
        cur.execute('INSERT INTO mcp_admissions VALUES (%s,%s,FLOOR(UNIX_TIMESTAMP()/60),1) ON DUPLICATE KEY UPDATE '
                    'requests=IF(window_start=VALUES(window_start),requests+1,1),window_start=VALUES(window_start)', (cfg.tenant, subject))
        cur.execute('SELECT requests FROM mcp_admissions WHERE tenant_id=%s AND subject_id=%s', (cfg.tenant, subject))
        count = cur.fetchone()['requests']
        conn.commit()
        if count > 120: raise Denied('MCP_RATE_LIMITED')


def iso(value):
    return '' if value is None else value.replace(tzinfo=timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')


def tracking(subject, order):
    with connection() as conn, conn.cursor() as cur:
        revision = grant(cur, subject, order)
        cur.execute('SELECT ledger_id FROM mcp_ledger_binding WHERE id=1')
        ledger = cur.fetchone()['ledger_id']
        cur.execute('SELECT /*+ MAX_EXECUTION_TIME(2000) */ id,carrier,status,shipped_at,delivered_at FROM shipments WHERE order_id=%s', (order,))
        shipment = cur.fetchone()
        if not shipment: raise Denied('MCP_NOT_FOUND')
        cur.execute('SELECT /*+ MAX_EXECUTION_TIME(2000) */ status,occurred_at FROM shipment_events WHERE shipment_id=%s '
                    'ORDER BY occurred_at DESC,id DESC LIMIT 21', (shipment['id'],))
        rows = cur.fetchall()
        if grant(cur, subject, order) != revision: raise Denied('MCP_ACCESS_DENIED')
        return dict(orderId=order, shipmentId=shipment['id'], carrier=shipment['carrier'], status=shipment['status'],
                    shippedAt=iso(shipment['shipped_at']), deliveredAt=iso(shipment['delivered_at']),
                    events=[dict(status=r['status'], occurredAt=iso(r['occurred_at'])) for r in reversed(rows[:20])],
                    truncated=len(rows) > 20, observedAt=iso(datetime.now(timezone.utc)), grantRevision=revision, ledgerId=ledger)


workers = asyncio.Semaphore(8)


async def bounded_db(work):
    # Cancellation of to_thread cannot stop a DB operation. Hold its permit until the real worker exits.
    try: await asyncio.wait_for(workers.acquire(), timeout=0.01)
    except TimeoutError: raise Denied('MCP_RATE_LIMITED') from None
    task = asyncio.create_task(asyncio.to_thread(work()))
    def finished(result):
        workers.release()
        if not result.cancelled(): result.exception()  # Retrieve late failures without logging sensitive exceptions.
    task.add_done_callback(finished)
    return await asyncio.shield(task)


def read_order(subject, order):
    admission(subject)
    return tracking(subject, order)


server = Server('workflow-shipment', version='1.0.0')


@server.list_tools()
async def list_tools():
    return [types.Tool(**CONTRACT)]


@server.call_tool(validate_input=False)
async def call_tool(name, arguments):
    started = time.monotonic()
    identity = get_access_token()
    code = 'OK'
    try:
        if identity is None: raise Denied('MCP_ACCESS_DENIED')
        if name != CONTRACT['name']: raise Denied('MCP_TOOL_BLOCKED')
        try: validate(arguments, CONTRACT['inputSchema'])
        except Exception: raise Denied('MCP_INVALID_ARGUMENT') from None
        if identity.expires_at <= time.time(): raise Denied('MCP_ACCESS_DENIED')
        async with asyncio.timeout(7):
            result = await bounded_db(lambda: read_order(identity.subject, arguments['orderId']))
        if identity.expires_at <= time.time(): raise Denied('MCP_ACCESS_DENIED')
        validate(result, CONTRACT['outputSchema'])
        return result  # SDK validates and emits both structuredContent and the JSON text form.
    except Denied as ex: code = ex.code
    except TimeoutError: code = 'MCP_TIMEOUT'
    except Exception: code = 'MCP_BACKEND_UNAVAILABLE'
    finally:
        # No bearer, order contents, raw RPC params or remote exception messages in logs.
        request_id = re.sub(r'[^A-Za-z0-9_-]', '_', str(server.request_context.request_id))[:80]
        log.info('mcp_call id=%s code=%s elapsed_ms=%d', request_id, code, int((time.monotonic()-started)*1000))
    return types.CallToolResult(isError=True, content=[types.TextContent(type='text', text=code)], structuredContent={'code': code})


manager = StreamableHTTPSessionManager(server, stateless=True, json_response=True, max_request_body_size=16384,
    security_settings=TransportSecuritySettings(enable_dns_rebinding_protection=True,
                                               allowed_hosts=cfg.hosts, allowed_origins=[v for v in cfg.origins if v]))
slots = asyncio.Semaphore(8)


class Endpoint:
    async def __call__(self, scope, receive, send):
        headers = dict(scope.get('headers', []))
        protocol = headers.get(b'mcp-protocol-version', PROTOCOL.encode()).decode('ascii', errors='replace')
        if protocol != PROTOCOL:
            await JSONResponse({'error': 'Unsupported protocol version'}, 400)(scope, receive, send)
            return
        try: await asyncio.wait_for(slots.acquire(), timeout=0.01)
        except TimeoutError:
            await JSONResponse({'error': 'MCP_BUSY'}, 429, headers={'Retry-After': '1'})(scope, receive, send)
            return
        started = False
        async def bounded_send(message):
            nonlocal started
            if message['type'] == 'http.response.start': started = True
            await send(message)
        try:
            async with asyncio.timeout(10):
                await manager.handle_request(scope, receive, bounded_send)
        except TimeoutError:
            if not started: await JSONResponse({'error': 'MCP_TIMEOUT'}, 504)(scope, receive, send)
        finally: slots.release()


async def metadata(request: Request):
    return JSONResponse({'resource': cfg.resource, 'authorization_servers': [cfg.issuer],
                         'scopes_supported': ['shipment:read'], 'bearer_methods_supported': ['header']})


async def health(request: Request):
    try:
        def check():
            with connection() as conn, conn.cursor() as cur:
                for table in ('mcp_order_grants', 'shipments', 'shipment_events', 'mcp_admissions', 'mcp_ledger_binding'):
                    cur.execute('SELECT 1 FROM ' + table + ' LIMIT 1')
        async with asyncio.timeout(4):
            await bounded_db(check)
        return JSONResponse({'status': 'UP', 'protocol': PROTOCOL})
    except Exception: return JSONResponse({'status': 'DOWN'}, 503)


@contextlib.asynccontextmanager
async def lifespan(app):
    async with manager.run(): yield


metadata_url = cfg.resource.removesuffix('/mcp') + '/.well-known/oauth-protected-resource/mcp'
protected = RequireAuthMiddleware(Endpoint(), ['shipment:read'], resource_metadata_url=AnyHttpUrl(metadata_url))
app = Starlette(routes=[Route('/mcp', protected, methods=['POST', 'GET', 'DELETE']),
                         Route('/.well-known/oauth-protected-resource/mcp', metadata), Route('/health/ready', health)],
                middleware=[Middleware(AuthenticationMiddleware, backend=BearerAuthBackend(Verifier())),
                            Middleware(AuthContextMiddleware)], lifespan=lifespan)
