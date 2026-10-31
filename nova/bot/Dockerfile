FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
RUN pip install --no-cache-dir "httpx>=0.27" "websockets>=13"
COPY nova_bot ./nova_bot
RUN useradd --create-home --uid 1000 nova
USER nova
CMD ["python", "-m", "nova_bot.main"]
