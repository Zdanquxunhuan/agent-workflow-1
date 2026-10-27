# Deployment rehearsal

The reproducible path is:

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17'
.\mvnw.cmd clean package -DskipTests
docker compose up --build -d
curl.exe http://localhost:8080/api/v1/health
docker compose ps
docker compose logs app
```

Compose sets `AGENT_PERSISTENCE=jdbc`, points the application at the `postgres` service, waits for the PostgreSQL health check, and lets Flyway apply V1 and V2. Stop and restart with `docker compose down` and `docker compose up -d`; the named volume preserves the database.

The current development environment has Docker Compose installed, but the Docker Desktop Linux engine was not reachable during the latest rehearsal (`permission denied` on the Docker API socket). Maven tests and the application context were verified with Java 17; the container rehearsal remains an environment-dependent check.
