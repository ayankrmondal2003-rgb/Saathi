"""Private object storage (Cloudflare R2 through its S3 API) with short-lived presigned URLs.

The phone uploads and downloads directly against the bucket; this server only signs URLs and records
metadata. Nothing is written to the local filesystem (Render's disk is ephemeral and shared by nothing).

AWS Signature Version 4, query-string form. Implemented here (about 60 lines) instead of pulling in
boto3/botocore, which would add tens of MB of memory on a 512 MB free instance. Verified against AWS's
published example in tests/test_storage.py.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import hmac
from urllib.parse import quote

import httpx

ALGORITHM = "AWS4-HMAC-SHA256"
UNSIGNED = "UNSIGNED-PAYLOAD"


def _encode(value: str, safe: str = "-_.~") -> str:
    return quote(value, safe=safe)


def _hmac(key: bytes, message: str) -> bytes:
    return hmac.new(key, message.encode(), hashlib.sha256).digest()


def presign(*, method: str, host: str, path: str, access_key: str, secret_key: str, region: str,
            expires: int, now: datetime, headers: dict[str, str] | None = None, scheme: str = "https") -> str:
    """Presigned URL for `method` on https://host/path. Every header in `headers` (plus host) is signed,
    so the client must send exactly those values, e.g. Content-Type and Content-Length on an upload."""
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    day = now.strftime("%Y%m%d")
    scope = f"{day}/{region}/s3/aws4_request"
    signed = {"host": host, **{k.lower(): str(v).strip() for k, v in (headers or {}).items()}}
    signed_names = ";".join(sorted(signed))
    query = {
        "X-Amz-Algorithm": ALGORITHM,
        "X-Amz-Credential": f"{access_key}/{scope}",
        "X-Amz-Date": amz_date,
        "X-Amz-Expires": str(expires),
        "X-Amz-SignedHeaders": signed_names,
    }
    canonical_query = "&".join(f"{_encode(k)}={_encode(v)}" for k, v in sorted(query.items()))
    canonical_uri = _encode(path, safe="-_.~/")
    canonical_headers = "".join(f"{name}:{signed[name]}\n" for name in sorted(signed))
    canonical_request = "\n".join([method, canonical_uri, canonical_query, canonical_headers, signed_names, UNSIGNED])
    string_to_sign = "\n".join([ALGORITHM, amz_date, scope, hashlib.sha256(canonical_request.encode()).hexdigest()])
    key = _hmac(_hmac(_hmac(_hmac(("AWS4" + secret_key).encode(), day), region), "s3"), "aws4_request")
    signature = hmac.new(key, string_to_sign.encode(), hashlib.sha256).hexdigest()
    return f"{scheme}://{host}{canonical_uri}?{canonical_query}&X-Amz-Signature={signature}"


@dataclass(frozen=True)
class Bucket:
    endpoint: str  # e.g. https://<account>.r2.cloudflarestorage.com
    bucket: str
    access_key: str
    secret_key: str
    region: str = "auto"  # R2 accepts "auto"

    @classmethod
    def from_settings(cls, settings) -> "Bucket | None":
        if not settings.storage_configured:
            return None
        endpoint = settings.r2_endpoint or f"https://{settings.r2_account_id}.r2.cloudflarestorage.com"
        return cls(endpoint.rstrip("/"), settings.r2_bucket, settings.r2_access_key_id,
                   settings.r2_secret_access_key.get_secret_value())

    def url(self, method: str, key: str, expires: int, headers: dict[str, str] | None = None,
            now: datetime | None = None) -> str:
        scheme, host = self.endpoint.split("://", 1)
        # Path-style addressing (/bucket/key) works for R2 and every S3-compatible store.
        return presign(method=method, host=host, path=f"/{self.bucket}/{key}", access_key=self.access_key,
                       secret_key=self.secret_key, region=self.region, expires=expires,
                       now=now or datetime.now(timezone.utc), headers=headers, scheme=scheme)

    async def head(self, key: str) -> int | None:
        """Size of the stored object, or None if it does not exist."""
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.head(self.url("HEAD", key, 60))
        if response.status_code == 404:
            return None
        response.raise_for_status()
        return int(response.headers.get("content-length", "0"))

    async def delete(self, key: str) -> None:
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.delete(self.url("DELETE", key, 60))
        if response.status_code not in (200, 204, 404):
            response.raise_for_status()
