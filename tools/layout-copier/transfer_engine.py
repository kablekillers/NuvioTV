"""Guarded single-platform layout transfer with backup, verification and rollback.

Not exposed as a public endpoint or wired to UI. The caller must provide an
authenticated, account-scoped adapter with pull() and push(payload) methods.
Never use the source account as destination.
"""
from copy import deepcopy
from layout_plan import plan

class TransferError(RuntimeError):
    pass

def transfer(source_layout, destination_adapter, confirmation):
    """Apply only after an explicit confirmation phrase and a fresh preview.

    destination_adapter.pull() returns decoded {"items":[...]}.
    destination_adapter.push(payload) writes only Home catalog settings.
    """
    if confirmation != "COPY HOME LAYOUT":
        raise TransferError("Explicit confirmation required")
    before=deepcopy(destination_adapter.pull())
    proposal=plan(source_layout,before)
    if not proposal["safe_to_apply"]:
        raise TransferError("Unmatched or ambiguous Home rows; no changes made")
    desired=proposal["preview"]
    # Avoid unnecessary writes if already identical.
    if desired==before:
        return {"status":"unchanged","matched":proposal["matched"],"backup":before}
    # Optimistic concurrency check: don't overwrite layout edited since preview.
    if destination_adapter.pull()!=before:
        raise TransferError("Destination changed during preview; no changes made")
    try:
        destination_adapter.push(desired)
        actual=destination_adapter.pull()
        if actual!=desired:
            raise TransferError("Read-back verification did not match proposed layout")
    except Exception as exc:
        try:
            destination_adapter.push(before)
            restored=destination_adapter.pull()
            if restored!=before:
                raise TransferError("Rollback verification failed; restore backup manually")
        except Exception as rollback_exc:
            raise TransferError("Transfer failed and rollback could not be verified") from rollback_exc
        raise TransferError("Transfer failed; original destination layout restored") from exc
    return {"status":"applied","matched":proposal["matched"],"backup":before}
