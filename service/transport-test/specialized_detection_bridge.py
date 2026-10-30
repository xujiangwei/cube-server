"""Optional loopback SHM peer. Uses the real Python action handler and dummy Unit.

No Python project files are changed; no models are downloaded and no camera is opened.
File storage is a test adapter returning a generated 32x24 image for Java FileLabels.
"""
import sys
from pathlib import Path
from threading import Event
from types import SimpleNamespace

sys.path.insert(0, sys.argv[1])
from cell.core import ActionDialect
from cell.nucleus import Nucleus
from cell.talk import TalkListener
from cube.aigc_service import AIGCService
from cube.packet import Packet
from cube.unit_specialized_detection import SpecializedDetectionUnit
from PIL import Image


class Storage:
    def downloadFile(self, label, directory, name):
        target = Path(directory) / name
        Image.new("RGB", (32, 24)).save(target, format="PNG")
        return str(target)


class Peer(TalkListener):
    def __init__(self):
        self.finished = Event()
        self.nucleus = Nucleus()
        self.unit = SpecializedDetectionUnit(dummy=True)
        self.unit.fileStorage = Storage()
        self.unit.initialize()
        self.service = SimpleNamespace(specializedDetectionUnit=self.unit, respond=self.respond)
        self.unit.aigcService = SimpleNamespace(transmit=self.transmit, tmpPath=None)

    def transmit(self, packet):
        return self.nucleus.talkService.speak("AIGC", packet.toDialect())

    def respond(self, request, packet):
        dialect = packet.toDialect()
        if request.containsParam("_notifier"):
            dialect.addParamAsJson("_notifier", request.getParam("_notifier"))
        self.nucleus.talkService.speak("AIGC", dialect)

    def onContacted(self, speaker):
        self.transmit(Packet("bridgeReady", {}))

    def onListened(self, speaker, cellet, primitive):
        dialect = ActionDialect(primitive)
        if dialect.getName() == "specializedDetection":
            AIGCService.executeSpecializedDetection(self.service, dialect)
        elif dialect.getName() == "bridgeDone":
            self.finished.set()


peer = Peer()
try:
    peer.nucleus.talkService.addListener(peer)
    assert peer.nucleus.talkService.call("127.0.0.1", int(sys.argv[2])) is not None
    assert peer.finished.wait(30), "Java did not finish within 30s"
finally:
    peer.unit.stop()
    peer.nucleus.destroy()
