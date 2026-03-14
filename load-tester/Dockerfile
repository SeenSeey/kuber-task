FROM golang:1.21-alpine AS deps
WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

FROM deps AS builder
COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /load-tester .

FROM alpine:3.19 AS runtime

RUN apk --no-cache add ca-certificates

WORKDIR /app

COPY --from=builder /load-tester /app/load-tester

ENTRYPOINT ["/app/load-tester"]
