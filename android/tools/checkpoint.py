"""Copy the built APK to an immutable checkpoint and write a JSON receipt with source identity."""
import hashlib, json, os, stat, sys, datetime, pathlib

root = pathlib.Path(sys.argv[1]).resolve()
label = sys.argv[2]
tests = sys.argv[3] if len(sys.argv) > 3 else ""
apk = root / "build" / "p71link-debug.apk"
data = apk.read_bytes()
sha = hashlib.sha256(data).hexdigest()
out = root / "build" / "checkpoints"
out.mkdir(parents=True, exist_ok=True)
name = f"p71link-{label}-{sha[:12]}"
dest = out / f"{name}.apk"
if dest.exists():
    if hashlib.sha256(dest.read_bytes()).hexdigest() != sha:
        sys.exit("checkpoint name collision with different content")
else:
    dest.write_bytes(data)
os.chmod(dest, stat.S_IRUSR)
sources = {}
for base in ("protocol/src", "app/src", "build.sh", "test.sh", "tools"):
    p = root / base
    files = [p] if p.is_file() else sorted(x for x in p.rglob("*") if x.is_file())
    for f in files:
        if "__pycache__" in f.parts:
            continue
        sources[str(f.relative_to(root))] = hashlib.sha256(f.read_bytes()).hexdigest()
tree = hashlib.sha256("".join(f"{k}\0{v}\n" for k, v in sorted(sources.items())).encode()).hexdigest()
receipt = {
    "created_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "kind": "connection checkpoint, development build",
    "apk_path": str(dest),
    "apk_sha256": sha,
    "apk_size_bytes": len(data),
    "application_id": "dev.p71link.app",
    "version_name": "0.1.0-dev",
    "min_sdk": 33,
    "target_sdk": 36,
    "signing": "APK Signature Scheme v3, task-owned development key (CN=P71 Link development build)",
    "signer_cert_sha256": "1e191d19b29f3a18ab52adff7fe26cf6aa665dcc25a0dfc5d644de56328823c1",
    "source_tree_sha256": tree,
    "source_files": sources,
    "offline_tests": tests,
    "phone_or_watch_tests_by_writer": "none",
    "not_final": "Checkpoint only. Full feature scope continues; see FEATURES.md.",
}
rp = out / f"{name}.json"
rp.write_text(json.dumps(receipt, indent=2) + "\n")
os.chmod(rp, stat.S_IRUSR)
print(rp)
