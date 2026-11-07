FROM searxng/searxng:latest

# Ship the reviewed instance configuration with the image.
COPY settings.yml /etc/searxng/settings.yml

ENV SEARXNG_SETTINGS_PATH=/etc/searxng/settings.yml
