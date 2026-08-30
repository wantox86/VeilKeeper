# --- Build stage ---
FROM golang:1.25-alpine AS builder

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .

# CGO disabled + static binary keeps the runtime image minimal (no libc
# dependency needed) and startup fast, per SPEC-BASE.md Section 5/52.
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/veilkeeper-api ./cmd/api

# --- Runtime stage ---
FROM alpine:3.20 AS runtime

RUN apk add --no-cache ca-certificates && \
    addgroup -S veilkeeper && \
    adduser -S -G veilkeeper -H -s /sbin/nologin veilkeeper

WORKDIR /app
COPY --from=builder /out/veilkeeper-api /app/veilkeeper-api

USER veilkeeper

EXPOSE 8080

ENTRYPOINT ["/app/veilkeeper-api"]
