from functools import lru_cache
import boto3
from botocore.config import Config

from app.config import settings


@lru_cache
def s3():
    cfg = settings()
    return boto3.client("s3", endpoint_url=cfg.s3_endpoint, aws_access_key_id=cfg.s3_access_key,
                        aws_secret_access_key=cfg.s3_secret_key, region_name="us-east-1",
                        config=Config(connect_timeout=3, read_timeout=15,
                                      retries={"max_attempts": 2}, s3={"addressing_style": "path"}))


def put_object(key, content):
    s3().put_object(Bucket=settings().s3_bucket, Key=key, Body=content,
                    ContentType="application/octet-stream")


def get_object(key):
    response = s3().get_object(Bucket=settings().s3_bucket, Key=key)
    with response["Body"] as stream:
        content = stream.read(settings().upload_max_bytes + 1)
    if len(content) > settings().upload_max_bytes:
        raise ValueError("Stored document exceeds upload limit")
    return content

