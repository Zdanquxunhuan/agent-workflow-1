# Build the pinned official source when Docker Hub/Quay/download servers are unavailable.
FROM public.ecr.aws/docker/library/golang:1.24.2-bookworm AS builder
ENV GOPROXY=https://goproxy.cn CGO_ENABLED=0 GOMAXPROCS=2
RUN go install github.com/minio/minio@RELEASE.2025-04-22T22-12-26Z
FROM python:3.12-slim-bookworm
COPY --from=builder /go/bin/minio /usr/local/bin/minio
RUN useradd --uid 10001 --create-home minio && mkdir /data && chown minio /data
USER minio
EXPOSE 9000 9001
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data", "--console-address", ":9001"]

