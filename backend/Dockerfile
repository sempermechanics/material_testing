# Pinned by digest for reproducible builds: the human tag can move, the digest
# cannot. Re-resolve with `docker buildx imagetools inspect python:3.12-slim`
# when intentionally bumping.
FROM python:3.12-slim@sha256:57cd7c3a7a273101a6485ba99423ee568157882804b1124b4dd04266317710de
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app/ ./app/
ENV PORT=8080

# Run as a non-root user rather than root (the default). Nothing here needs
# privilege at runtime, and Cloud Run has no reason to run this as uid 0.
RUN useradd --system --no-create-home --uid 10001 appuser
USER appuser

CMD exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --workers 2
