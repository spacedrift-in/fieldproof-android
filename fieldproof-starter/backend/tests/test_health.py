from fastapi.testclient import TestClient

from backend.app.main import app

client = TestClient(app)


def test_health_returns_ok() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "fieldproof-api"
    # ``stage`` now reflects APP_ENV/ENVIRONMENT (defaults to "development").
    assert body["stage"]
