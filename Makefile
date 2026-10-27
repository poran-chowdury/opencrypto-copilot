.PHONY: up down logs build test verify local stop web dashboard
up:        ## everything in Docker (UI on :5173)
	docker compose up --build -d
down:
	docker compose down
logs:
	docker compose logs -f --tail=200
build:
	mvn -B clean package -DskipTests
test:      ## unit tests only (fast, no Docker)
	mvn -B test
verify:    ## unit + Testcontainers integration tests (needs Docker)
	mvn -B verify
local:     ## infra in Docker, services on this machine
	scripts/run-local.sh
stop:
	scripts/stop-local.sh
web:
	cd frontend && npm install && npm run dev
dashboard: ## regenerate the Grafana dashboard JSON
	python infra/observability/grafana/generate_dashboard.py
