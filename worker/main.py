import os, json
from worker.fireutils import set_status
from worker.qupath import run_qupath
from worker.cellpose import run_cellpose

JOB_ID = os.environ["JOB_ID"]
MODE   = os.environ.get("MODE","40X")
GS_IN  = os.environ["GS_INPUT"]

set_status(JOB_ID, status="running", stage="prepare", pct=5)
results = run_qupath(GS_IN) if MODE == "40X" else run_cellpose(GS_IN)
set_status(JOB_ID, status="succeeded", stage="done", pct=100, result_refs=results)
print(json.dumps({"ok": True, "job_id": JOB_ID, "results": results}))
