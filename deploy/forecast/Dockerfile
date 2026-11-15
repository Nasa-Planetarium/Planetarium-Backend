# FireOutlook Planner service image: the model repo at the pinned commit (git archive, no code change), its locked
# dependencies (uv sync --frozen --no-dev from its uv.lock) and the thin wrapper (server.py, layout.py, refresh.py).
# Built by deploy/forecast/build.sh, which assembles the context: model.tar (git archive <commit>) + the wrapper files.
ARG PYTHON_IMAGE=python:3.13-slim-bookworm

FROM ${PYTHON_IMAGE} AS build
ARG UV_VERSION=0.12.18
ENV UV_PYTHON_DOWNLOADS=never UV_LINK_MODE=copy UV_COMPILE_BYTECODE=1 UV_PROJECT_ENVIRONMENT=/opt/model/.venv
RUN pip install --no-cache-dir "uv==${UV_VERSION}"
WORKDIR /opt/model
ADD model.tar /opt/model/
RUN uv sync --frozen --no-dev --python /usr/local/bin/python3.13 \
 && rm -rf /root/.cache

FROM ${PYTHON_IMAGE}
ARG MODEL_COMMIT
LABEL org.opencontainers.image.title="planetarium-forecast" \
      io.emberline.model-commit="${MODEL_COMMIT}"
RUN groupadd --system --gid 10001 forecast \
 && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin forecast
COPY --from=build /opt/model /opt/model
COPY server.py layout.py refresh.py /opt/forecast/
# PROCESSED_DATA_DIR is <model root>/data/processed (hard-coded): data is a link to the writable view link in /tmp.
RUN chmod 0755 /opt/forecast && chmod 0644 /opt/forecast/*.py \
 && ln -s /tmp/pl-data /opt/model/data \
 && echo "${MODEL_COMMIT}" > /opt/model/MODEL_COMMIT \
 && python -m compileall -q /opt/forecast
ENV PATH=/opt/model/.venv/bin:$PATH PYTHONPATH=/opt/model PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    FORECAST_MODEL_ROOT=/opt/model FORECAST_DATA_LINK=/tmp/pl-data FORECAST_PORT=8000 \
    MPLCONFIGDIR=/tmp/matplotlib
USER 10001
WORKDIR /opt/model
EXPOSE 8000
ENTRYPOINT ["python", "/opt/forecast/server.py"]
