from celery import Celery
from kombu import Queue
from app.config import settings

celery_app = Celery("rag", broker=settings().broker_url)
celery_app.conf.update(
    task_serializer="json", accept_content=["json"], result_serializer="json",
    task_ignore_result=True, task_acks_late=True, task_reject_on_worker_lost=True,
    worker_prefetch_multiplier=1, task_default_queue="rag.ingest",
    task_queues=[Queue("rag.ingest", durable=True, queue_arguments={"x-queue-type": "quorum"})],
    task_default_delivery_mode="persistent", broker_connection_timeout=3,
    broker_transport_options={"confirm_publish": True},
    broker_connection_retry_on_startup=True,
)

