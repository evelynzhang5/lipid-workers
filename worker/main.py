# lipid-workers/worker/main.py

import os
import tempfile
import traceback
from google.cloud import firestore, storage

from worker.qupath import run_qupath
from worker.cellpose import run_cellpose

# Init clients
db = firestore.Client(project=os.environ["FIRESTORE_PROJECT"])
gcs = storage.Client(project=os.environ["FIRESTORE_PROJECT"])
RESULTS_BUCKET = os.environ["RESULTS_BUCKET"]

def set_status(job_id: str, **fields):
    """Update Firestore job doc with minimal writes."""
    fields["updated_at"] = firestore.SERVER_TIMESTAMP
    db.collection("jobs").document(job_id).set(fields, merge=True)

def run_worker(job_id: str, mode: str, gs_input: str):
    try:
        # 1) Start job
        set_status(job_id, status="running", stage="prepare", pct=5)

        # Local temp dir for outputs
        out_dir = tempfile.mkdtemp()

        # 2) Run segmentation
        set_status(job_id, stage="segmentation", pct=25)

        if mode == "40X":
            uploaded = run_qupath(gs_input, out_dir, job_id)
        elif mode == "20X":
            uploaded = run_cellpose(gs_input, out_dir, job_id)
        else:
            raise ValueError(f"Unknown mode: {mode}")

        # 3) Processing finished
        set_status(job_id, stage="processing", pct=75)

        # 4) Final atomic write: results + success
        db.collection("jobs").document(job_id).update({
            "status": "succeeded",
            "stage": "done",
            "pct": 100,
            "result_refs": uploaded,  # dict of rel_path -> gs://bucket/path
            "finished_at": firestore.SERVER_TIMESTAMP,
            "updated_at": firestore.SERVER_TIMESTAMP
        })

    except Exception as e:
        # On failure: single atomic error update
        tb = traceback.format_exc()
        db.collection("jobs").document(job_id).update({
            "status": "failed",
            "stage": "error",
            "error": {"message": str(e), "trace": tb[-2000:]},  # truncate trace
            "updated_at": firestore.SERVER_TIMESTAMP
        })
        raise
if __name__ == "__main__":
    # Cloud Run Job passes these as per-execution env overrides
    JOB_ID   = os.environ["JOB_ID"]           # required
    MODE     = os.environ.get("MODE", "40X")  # "40X" | "20X"
    GS_INPUT = os.environ["GS_INPUT"]         # gs://lipid-inputs/...

    run_worker(JOB_ID, MODE, GS_INPUT)
