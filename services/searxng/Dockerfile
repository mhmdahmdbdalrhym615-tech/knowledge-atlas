FROM searxng/searxng:latest

# Ship the reviewed settings and limiter overrides with the image.
COPY settings.yml /etc/searxng/settings.yml
COPY limiter.toml /etc/searxng/limiter.toml

ENV SEARXNG_SETTINGS_PATH=/etc/searxng/settings.yml
