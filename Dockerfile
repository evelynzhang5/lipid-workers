FROM python:3.11-slim

# --- System deps incl. Java & ImageIO plugins ---
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jre-headless curl unzip \
    libglib2.0-0 libsm6 libxrender1 libxext6 \
    libtwelvemonkeys-java libjai-imageio-core-java \
  && rm -rf /var/lib/apt/lists/*

# Headless rendering + discover ImageIO plugins
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"
ENV CLASSPATH="/usr/share/java/jai-imageio-core.jar:/usr/share/java/twelvemonkeys-imageio.jar:/usr/share/java/twelvemonkeys-imageio-tiff.jar:/usr/share/java/twelvemonkeys-imageio-core.jar"
# Optional: heap for QuPath (override at deploy if you prefer)
# ENV QUPATH_JAVA_OPTS="-Xmx4g"

# --- QuPath (pin version) ---
ENV QUPATH_VERSION=0.5.1
RUN curl -L -o /tmp/qupath.zip https://github.com/qupath/qupath/releases/download/v${QUPATH_VERSION}/QuPath-v${QUPATH_VERSION}-Linux.zip \
    && unzip /tmp/qupath.zip -d /opt \
    && ln -s /opt/QuPath*/bin/QuPath /usr/local/bin/qupath

# --- Python deps ---
WORKDIR /worker
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# --- Your code + assets ---
COPY worker ./worker
COPY scripts ./scripts
COPY models  ./models

# Runtime niceties
ENV PYTHONUNBUFFERED=1

# Run as non-root
RUN useradd -m appuser && chown -R appuser:appuser /worker /opt
USER appuser

# No project-specific env baked in; set at deploy:
# FIRESTORE_PROJECT, RESULTS_BUCKET, JOB_ID, MODE, GS_INPUT

CMD ["python","-m","worker.main"]
