FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /app
RUN pip install --index-url https://download.pytorch.org/whl/cpu 'torch==2.15.0+cpu'
COPY requirements.in requirements.lock ./
ARG PIP_INDEX_URL=https://pypi.org/simple
RUN pip install --index-url ${PIP_INDEX_URL} -c requirements.lock -r requirements.in
RUN useradd --uid 10001 --create-home rag && mkdir /models && chown rag /models
COPY server.py reranker_server.py reranker_config.py download_reranker.py ./
USER rag
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8092", "--no-access-log"]

