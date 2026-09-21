FROM public.ecr.aws/docker/library/python:3.12-slim-bookworm@sha256:392307d22300de8b5986851a12d9176dfc0fc073e65bf6523ebd7dcbeb23564e
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 LANG=C.UTF-8
COPY requirements.lock /opt/requirements.lock
RUN pip install --no-cache-dir -r /opt/requirements.lock \
    && mkdir -p /work /opt/ruoyi
COPY files.py sweep.py /opt/ruoyi/
RUN chmod 0555 /opt/ruoyi /opt/ruoyi/*.py
WORKDIR /work
USER 65534:65534
CMD ["sleep", "1800"]
