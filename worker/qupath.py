# worker/qupath.py
import os, tempfile, subprocess, json
from pathlib import Path
from google.cloud import storage

PROJECT = os.environ["FIRESTORE_PROJECT"]
RESULTS_BUCKET = os.environ["RESULTS_BUCKET"]

gcs = storage.Client(project=PROJECT)

def _gcs_download(gs_uri: str, local_path: str):
    assert gs_uri.startswith("gs://")
    bucket, key = gs_uri[5:].split("/", 1)
    b = gcs.bucket(bucket); blob = b.blob(key)
    Path(local_path).parent.mkdir(parents=True, exist_ok=True)
    blob.download_to_filename(local_path)
    return local_path

def _gcs_upload(local_path: str, dst: str) -> str:
    b = gcs.bucket(RESULTS_BUCKET)
    blob = b.blob(dst)
    blob.upload_from_filename(local_path)
    return f"gs://{b.name}/{blob.name}"

def run_qupath(gs_input: str):
    """
    Executes QuPath headless with the lipid 40x Groovy pipeline.
    Outputs are organized under outDir: documents/, images/, masks/, overlays.geojson
    Everything is uploaded to: gs://RESULTS_BUCKET/JOB_ID/<relative_path>
    """
    job_id   = os.environ["JOB_ID"]
    script   = "/worker/scripts/qupath_headless.groovy"
    classifier_path = "/worker/models/lipid_1.json"

    work = Path(tempfile.mkdtemp())
    out_dir = work / "out"; out_dir.mkdir(parents=True, exist_ok=True)
    wsi_path = work / "input.ome.tif"

    # 1) download input
    _gcs_download(gs_input, str(wsi_path))

    # 2) run QuPath
    # export_geojson=true by default; export_mask=true uses TIFF writer (enabled via Dockerfile)
    args_kv = [
        f"out={out_dir}",
        f"classifier={classifier_path}",
        "export_geojson=true",
        "export_mask=true",
        "ds=16"
    ]
    cmd = [
        "qupath","script",
        "--image", str(wsi_path),
        "--script", script,
        "--args", ";".join(args_kv)
    ]
    print("Running QuPath:", " ".join(cmd))
    try:
        proc = subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        print(proc.stdout)
    except subprocess.CalledProcessError as e:
        print("QuPath failed output:\n", e.stdout)
        raise

    # 3) upload results recursively, preserving folder structure
    uploaded = {}
    for p in out_dir.rglob("*"):
        if p.is_dir(): 
            continue
        rel = p.relative_to(out_dir).as_posix()   # e.g. 'documents/foo.csv', 'images/roi_1.png'
        uri = _gcs_upload(str(p), f"{job_id}/{rel}")
        uploaded[rel] = uri

    # 4) add a manifest
    manifest = out_dir / "manifest.json"
    manifest.write_text(json.dumps({
        "inputs": {"image": str(wsi_path)},
        "outputs": sorted(list(uploaded.keys()))
    }, indent=2))
    uploaded["manifest.json"] = _gcs_upload(str(manifest), f"{job_id}/manifest.json")

    return uploaded
