# ==============================================================================
# DeltaFlow Operational Makefile
# ==============================================================================

.PHONY: help up down restart ps logs build build-images clean smoke test

help:
	@echo "Available commands for DeltaFlow:"
	@echo "  make up           - Start all 7 services in background via Docker Compose"
	@echo "  make down         - Gracefully stop and remove containers"
	@echo "  make restart      - Restart all running containers"
	@echo "  make ps           - List container statuses and health probes"
	@echo "  make logs         - Follow aggregated container logs"
	@echo "  make build        - Build Maven executable JAR locally"
	@echo "  make build-images - Rebuild Docker Compose container images"
	@echo "  make test         - Run full test suite with Testcontainers"
	@echo "  make smoke        - Execute automated health & financial smoke tests"
	@echo "  make clean        - Stop containers and remove all persistent volumes"

up:
	docker compose up -d

down:
	docker compose down

restart:
	docker compose restart

ps:
	docker compose ps

logs:
	docker compose logs -f

build:
	mvn clean package -DskipTests

build-images:
	docker compose build

test:
	mvn test -B

smoke:
	chmod +x ./scripts/smoke-test.sh
	./scripts/smoke-test.sh

clean:
	docker compose down -v
