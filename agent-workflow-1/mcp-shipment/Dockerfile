FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 SHIPMENT_CONTRACT_PATH=/app/contract.json
WORKDIR /app
COPY mcp-shipment/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10002 --create-home mcp
COPY mcp-shipment/app.py ./
COPY src/main/resources/mcp/shipment-v1.json ./contract.json
USER 10002
EXPOSE 8094
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8094", "--limit-concurrency", "32", "--timeout-keep-alive", "5", "--no-proxy-headers", "--no-access-log"]
