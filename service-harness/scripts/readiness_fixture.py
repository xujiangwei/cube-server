"""Registration-only model peer for transport tests; queued work is deliberately not polled."""
import threading
from pathlib import Path
from harness.model_worker import ModelHarnessService


class RegistrationOnlyModel(ModelHarnessService):
    def exchange_work(self):
        pass


class RegisteredModel:
    def __init__(self, config):
        Path(config["runtimeDir"]).mkdir(parents=True, exist_ok=True)
        self.service = RegistrationOnlyModel(config)
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self._run, name="transport-model-registration", daemon=True)
        self.thread.start()

    def _run(self):
        while not self.stopped.is_set():
            try:
                if not self.service.registered or not self.service.connected.is_set():
                    self.service.close()
                    self.service.connect()
                self.service.request("heartbeat")
            except (OSError, RuntimeError, TimeoutError):
                self.service.close()
            self.stopped.wait(.25)

    def close(self):
        self.stopped.set()
        self.thread.join(timeout=10)
        self.service.shutdown()
        self.service.executor.shutdown(wait=True)
