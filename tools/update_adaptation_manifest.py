"""Generate the GitHub manifest from canonical v2 profiles. Run from any directory."""
import argparse
import hashlib
import json
from pathlib import Path


def generate(directory):
    entries = []
    filenames = json.loads((directory / "index.json").read_text(encoding="utf-8"))
    assert 0 < len(filenames) <= 64 and len(set(filenames)) == len(filenames)
    targets = set()
    for name in filenames:
        assert Path(name).name == name and name not in ("index.json", "manifest.json")
        raw = (directory / name).read_bytes()
        profile = json.loads(raw)
        assert len(raw) <= 256 * 1024 and profile["schemaVersion"] == 2
        assert profile["hookContract"] == "guoplus-hooks-v1" and profile["revision"] > 0
        host = profile["host"]
        prefix = "gp" if host["packageName"] == "com.phoenix.read" else "gp-oversea"
        assert name == f"{prefix}-{host['versionName']}-h{profile['revision']:03d}.json", "Filename must match host version and revision"
        identity = (host["packageName"], host["versionName"], profile["versionCode"])
        assert identity not in targets, "Duplicate host version"
        targets.add(identity)
        entries.append(dict(file=name, **host, versionCode=profile["versionCode"],
                            revision=profile["revision"], sha256=hashlib.sha256(raw).hexdigest()))
    return json.dumps(dict(schemaVersion=1, hookContract="guoplus-hooks-v1", profiles=entries),
                      indent=2, ensure_ascii=False) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if the manifest is stale")
    args = parser.parse_args()
    folder = Path(__file__).resolve().parents[1] / "adaptation"
    output = generate(folder)
    manifest = folder / "manifest.json"
    if args.check:
        assert manifest.read_text(encoding="utf-8") == output, "Run tools/update_adaptation_manifest.py"
        print("Adaptation manifest verified")
    else:
        manifest.write_text(output, encoding="utf-8", newline="\n")
        print(f"Updated {manifest}")
