"""Local-only guarded transfer runner with a durable pre-write backup.

This is intentionally not an HTTP endpoint. The caller supplies source layout,
destination adapter, and a previously reviewed preview receipt. A backup is
created exclusively and fsynced BEFORE any write RPC is permitted.
"""
import json
import os
from pathlib import Path
from preview_receipt import apply_with_receipt
from transfer_engine import TransferError

def save_backup_exclusive(path, layout):
    target=Path(path)
    if target.exists():
        raise TransferError("Backup path already exists; refusing to overwrite it")
    if not target.parent.is_dir():
        raise TransferError("Backup directory does not exist")
    data=json.dumps(layout,indent=2,ensure_ascii=False).encode("utf-8")
    fd=os.open(str(target),os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    try:
        with os.fdopen(fd,"wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    except Exception:
        # A partial file is retained as evidence rather than overwritten.
        raise
    return str(target)

def apply_with_durable_backup(source, destination_adapter, receipt, confirmation,
                              source_profile, destination_profile, backup_path, source_loader=None):
    if confirmation!="COPY HOME LAYOUT":
        raise TransferError("Explicit confirmation required")
    # Check receipt and preview before creating a backup.
    from preview_receipt import preview_receipt, digest
    if source_loader is not None:
        source=source_loader()  # Re-read source to detect changes since preview.
    current=destination_adapter.pull()
    fresh=preview_receipt(source,current,destination_adapter.platform,
                          source_profile,destination_profile)
    if not fresh["safe_to_apply"] or digest(fresh)!=digest(receipt):
        raise TransferError("Preview is stale or unsafe; no changes made")
    saved=save_backup_exclusive(backup_path,current)
    # The engine re-pulls destination and rejects changed data.
    result=apply_with_receipt(source,destination_adapter,receipt,confirmation,
                              source_profile,destination_profile)
    return {"status":result["status"],"matched":result["matched"],
            "backup_path":saved}
