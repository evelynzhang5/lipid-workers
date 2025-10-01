import os
from google.cloud import firestore
db = firestore.Client(project=os.environ["FIRESTORE_PROJECT"])

def set_status(job_id, **fields):
    db.collection("jobs").document(job_id).set(fields, merge=True)
