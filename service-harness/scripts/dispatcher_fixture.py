"""Private real Performer/Jetty fixture. Business requests use SHM; health/admin stay on service HTTP."""
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request


class DispatcherFixture:
    def __init__(self, java, classpath, cell_port, api_credential, runtime):
        self.java = Path(java)
        self.api_credential = api_credential
        self.root = Path(runtime) / "dispatcher"
        self.root.mkdir(parents=True, exist_ok=False)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            self.port = sock.getsockname()[1]
        module = Path(__file__).resolve().parents[1]
        dispatcher = module.parent / "build/cube-dispatcher-3.0.jar"
        if not dispatcher.is_file():
            raise RuntimeError("Build cube-dispatcher with JDK 8 before running Harness transport smokes")
        classes = self.root / "classes"
        classes.mkdir()
        cp = os.pathsep.join((str(dispatcher), str(classes), classpath))
        source = module / "transport-test/cube/service/harness/test/HarnessGatewayFixture.java"
        self.log = (self.root / "dispatcher.log").open("wb")
        subprocess.run([str(self.java.with_name("javac.exe" if os.name == "nt" else "javac")), "-encoding", "UTF-8",
                        "-source", "1.8", "-target", "1.8", "-cp", cp, "-d", str(classes), str(source)],
                       stdout=self.log, stderr=subprocess.STDOUT, check=True, timeout=30)
        config = self.root / "dispatcher.local.properties"
        config.write_text("\n".join(("service.port=" + str(cell_port), "http.port=" + str(self.port))) + "\n", encoding="utf-8")
        if os.name != "nt":
            self.root.chmod(0o700)
            config.chmod(0o600)
        self.stop_file = self.root / "stop"
        self.process = subprocess.Popen([str(self.java), "-Xmx256m", "-cp", cp,
                                         "cube.service.harness.test.HarnessGatewayFixture", str(config), str(self.stop_file)],
                                        cwd=self.root, stdin=subprocess.DEVNULL, stdout=self.log, stderr=subprocess.STDOUT,
                                        **({"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}))

    def url(self, path, service_http_port):
        port = self.port if path.startswith("/harness/v1/") else service_http_port
        return "http://127.0.0.1:" + str(port) + path

    def ready(self, timeout=25):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if self.process.poll() is not None:
                raise RuntimeError("Harness dispatcher fixture exited; inspect its private log")
            try:
                request = urllib.request.Request(self.url("/harness/v1/capabilities", 0),
                            headers={"Authorization": "Bearer " + self.api_credential})
                with urllib.request.urlopen(request, timeout=2) as response:
                    if response.status == 200:
                        return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(.1)
        raise RuntimeError("Harness SHM dispatcher did not become ready")

    def close(self):
        self.stop_file.touch()
        try:
            if self.process.wait(timeout=15) != 0:
                raise RuntimeError("Harness dispatcher fixture shutdown failed")
        except subprocess.TimeoutExpired:
            self.process.terminate()
            self.process.wait(timeout=10)
            raise
        finally:
            self.log.close()
