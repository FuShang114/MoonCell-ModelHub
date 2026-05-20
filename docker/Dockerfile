# Build stage - use Debian based golang image
FROM golang:1.22 AS builder

WORKDIR /app

# Install dependencies
RUN apt-get update && apt-get install -y git gcc sqlite3 libsqlite3-dev && rm -rf /var/lib/apt/lists/*

# Use Chinese proxy for Go modules
ENV GOPROXY=https://goproxy.cn,https://proxy.golang.org,direct
ENV GOSUMDB=off

# Copy go mod files
COPY go.mod ./
RUN go mod download

# Copy source code
COPY . .

# Download dependencies and build
RUN go mod tidy && CGO_ENABLED=1 GOOS=linux go build -ldflags="-s -w" -o modelhub ./cmd/server

# Runtime stage - use Debian slim (smaller than full Debian)
FROM debian:bookworm-slim

WORKDIR /app

# Install runtime dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    sqlite3 \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy binary from builder
COPY --from=builder /app/modelhub .

# Create data directory for SQLite
RUN mkdir -p /app/data

# Set environment variables
ENV SERVER_PORT=8080
ENV DATABASE_PATH=/app/data/modelhub.db
ENV SERVER_MODE=release
ENV QUEUE_CAPACITY=1000
ENV RETRY_TIMES=3
ENV REQUEST_TIMEOUT=60

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/api/status || exit 1

# Run binary
CMD ["./modelhub"]