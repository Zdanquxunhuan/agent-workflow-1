"""Generate the API contract without starting lifespan, contacting models or applying migrations."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.main import app

if __name__ == '__main__':
    target = Path(sys.argv[1])
    target.write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
