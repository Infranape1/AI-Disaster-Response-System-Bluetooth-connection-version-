from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional
import re
import hashlib
import datetime
import json
import os
import socket
import threading


app = FastAPI(
    title="AI Disaster Response API",
    version="0.2.0"
)


app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


reports = []


# ---------------------------------------------------------
# PERSISTENCE
#
# Reports used to live only in memory, so every restart of the
# server wiped the dashboard. They are now mirrored to a file.
# ---------------------------------------------------------

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STORE_PATH = os.path.join(BASE_DIR, "reports.json")


def load_reports():

    global reports

    if not os.path.exists(STORE_PATH):
        return

    try:
        with open(STORE_PATH, "r", encoding="utf-8") as handle:
            reports = json.load(handle)

        print(f"[STORE] loaded {len(reports)} report(s)")

    except Exception as error:
        print(f"[STORE] could not load: {error}")


def save_reports():

    try:
        with open(STORE_PATH, "w", encoding="utf-8") as handle:
            json.dump(reports, handle, indent=2)

    except Exception as error:
        print(f"[STORE] could not save: {error}")


load_reports()


# ---------------------------------------------------------
# LAN DISCOVERY BEACON
#
# A phone cannot guess the address of this PC, and typing it by
# hand is the single most common reason reports never arrive.
# The app sends a UDP broadcast, this thread answers with the
# address and port to use.
# ---------------------------------------------------------

DISCOVERY_PORT = 8765
DISCOVERY_REQUEST = b"DISASTER_DISCOVER"
API_PORT = int(os.environ.get("DISASTER_PORT", "8000"))


def lan_ip():
    """Best guess at this machine's address on the local network."""

    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    try:
        # No packet is actually sent; this just picks the outbound
        # interface the OS would use.
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]

    except Exception:
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return "127.0.0.1"

    finally:
        probe.close()


def all_lan_ips():
    """
    Every private IPv4 address this machine has, across all adapters.

    lan_ip() alone can pick the wrong one when a VPN, Docker, Hyper-V or
    WSL virtual adapter is active, because those often win the "which
    interface would a packet to 8.8.8.8 use" check. Printing every
    candidate lets a human confirm the right one instead of guessing.
    """

    import socket as _socket

    candidates = []

    try:
        hostname = _socket.gethostname()
        for info in _socket.getaddrinfo(hostname, None, _socket.AF_INET):
            ip = info[4][0]
            if ip.startswith("127."):
                continue
            if ip not in candidates:
                candidates.append(ip)
    except Exception:
        pass

    primary = lan_ip()
    if primary not in candidates and not primary.startswith("127."):
        candidates.insert(0, primary)

    return candidates


def discovery_server():

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        sock.bind(("", DISCOVERY_PORT))

    except Exception as error:
        print(f"[DISCOVERY] disabled: {error}")
        return

    print(f"[DISCOVERY] listening on UDP {DISCOVERY_PORT}")

    while True:

        try:
            data, addr = sock.recvfrom(1024)

            if DISCOVERY_REQUEST not in data:
                continue

            reply = json.dumps({
                "service": "AI-DISASTER-RESPONSE",
                "host": lan_ip(),
                "port": API_PORT,
                "sync": "/sync"
            }).encode("utf-8")

            sock.sendto(reply, addr)

            print(f"[DISCOVERY] answered {addr[0]}")

        except Exception as error:
            print(f"[DISCOVERY] error: {error}")


threading.Thread(target=discovery_server, daemon=True).start()


class Report(BaseModel):
    messageId: str
    hubId: str
    senderNodeId: str
    timestamp: str
    type: str = "GENERAL"
    content: str
    ttl: int = 10


# ---------------------------------------------------------
# PRIVACY / PII REDACTION
# ---------------------------------------------------------

def redact(text: str):

    # Indian-style 10 digit phone numbers
    text = re.sub(
        r"\b[6-9]\d{9}\b",
        "[PHONE REDACTED]",
        text
    )

    # Email addresses
    text = re.sub(
        r"[\w.+-]+@[\w-]+\.[\w.-]+",
        "[EMAIL REDACTED]",
        text
    )

    return text


# ---------------------------------------------------------
# EMERGENCY CLASSIFICATION
# ---------------------------------------------------------

def classify(content: str, emergency_type: str = "GENERAL"):

    text = content.lower().strip()
    selected_type = emergency_type.upper().strip()

    # -----------------------------------------------------
    # 1. EXPLICIT ANDROID EMERGENCY TYPE
    # -----------------------------------------------------

    type_mapping = {

        "MEDICAL":
            ("Medical Emergency", "HIGH"),

        "RESCUE":
            ("People Trapped", "CRITICAL"),

        "FIRE":
            ("Fire", "CRITICAL"),

        "FLOOD":
            ("Flood", "HIGH"),

        "EARTHQUAKE":
            ("Earthquake", "CRITICAL"),

        "ROAD_BLOCKED":
            ("Road Blocked", "HIGH"),

        "SUPPLIES":
            ("Food / Water / Supplies", "MEDIUM"),

        "GENERAL":
            (None, None)
    }

    selected_disaster, selected_severity = \
        type_mapping.get(
            selected_type,
            (None, None)
        )


    # -----------------------------------------------------
    # 2. CRITICAL KEYWORDS
    # -----------------------------------------------------

    critical_keywords = [

        "dead",
        "death",
        "died",
        "dying",
        "fatal",
        "fatality",
        "life threatening",
        "life-threatening",
        "unconscious",
        "not breathing",
        "severe bleeding",
        "major bleeding",
        "people trapped",
        "person trapped",
        "trapped under",
        "trapped inside",
        "buried",
        "collapsed building",
        "building collapse",
        "rescue urgently",
        "urgent rescue",
        "urgently need rescue"
    ]


    # -----------------------------------------------------
    # 3. HIGH SEVERITY KEYWORDS
    # -----------------------------------------------------

    high_keywords = [

        "injured",
        "injury",
        "injuries",
        "ambulance",
        "medical emergency",
        "hospital",
        "fire",
        "burning",
        "smoke",
        "flood",
        "flooding",
        "water entering",
        "earthquake",
        "earthquake damage",
        "collapsed",
        "collapse",
        "bridge collapsed",
        "road blocked",
        "roadblock",
        "evacuate",
        "evacuation"
    ]


    # -----------------------------------------------------
    # 4. DISASTER CATEGORY DETECTION
    # -----------------------------------------------------

    if any(word in text for word in [
        "flood",
        "flooding",
        "water entering",
        "water inside",
        "water level rising",
        "house underwater"
    ]):

        detected_disaster = "Flood"

    elif any(word in text for word in [
        "fire",
        "burning",
        "flames",
        "smoke",
        "building on fire"
    ]):

        detected_disaster = "Fire"

    elif any(word in text for word in [
        "earthquake",
        "earthquake damage",
        "shaking",
        "ground shaking",
        "building shaking"
    ]):

        detected_disaster = "Earthquake"

    elif any(word in text for word in [
        "road blocked",
        "roadblock",
        "road is blocked",
        "bridge collapsed",
        "bridge is collapsed"
    ]):

        detected_disaster = "Road Blocked"

    elif any(word in text for word in [
        "trapped",
        "people trapped",
        "person trapped",
        "stuck under debris",
        "buried under debris",
        "need rescue",
        "rescue needed",
        "rescue"
    ]):

        detected_disaster = "People Trapped"

    elif any(word in text for word in [
        "medical",
        "injured",
        "injury",
        "injuries",
        "ambulance",
        "doctor",
        "hospital",
        "bleeding",
        "unconscious",
        "not breathing"
    ]):

        detected_disaster = "Medical Emergency"

    elif any(word in text for word in [
        "food",
        "water",
        "drinking water",
        "supplies",
        "medicine shortage",
        "need supplies"
    ]):

        detected_disaster = "Food / Water / Supplies"

    else:

        detected_disaster = "Unknown"


    # -----------------------------------------------------
    # 5. SEVERITY DETECTION
    # -----------------------------------------------------

    if any(word in text for word in critical_keywords):

        detected_severity = "CRITICAL"

    elif any(word in text for word in high_keywords):

        detected_severity = "HIGH"

    else:

        detected_severity = "MEDIUM"


    # -----------------------------------------------------
    # 6. PRIORITIZE EXPLICIT TYPE
    # -----------------------------------------------------

    if selected_disaster is not None:

        disaster = selected_disaster

        # Description can increase severity,
        # but should not reduce the severity selected
        # by the emergency type.

        severity_order = {
            "MEDIUM": 1,
            "HIGH": 2,
            "CRITICAL": 3
        }

        if severity_order[detected_severity] > \
                severity_order[selected_severity]:

            severity = detected_severity

        else:

            severity = selected_severity

    else:

        disaster = detected_disaster
        severity = detected_severity


    # -----------------------------------------------------
    # 7. GENERAL "HELP" REQUESTS
    # -----------------------------------------------------

    if disaster == "Unknown":

        if any(word in text for word in [
            "help",
            "emergency",
            "assistance",
            "need help",
            "please help"
        ]):

            disaster = "General Emergency"


    return disaster, severity


# ---------------------------------------------------------
# HEALTH
# ---------------------------------------------------------

@app.get("/health")
def health():

    return {
        "ok": True,
        "reports": len(reports)
    }


# ---------------------------------------------------------
# SYNC
# ---------------------------------------------------------

@app.post("/sync")
def sync(report: Report):

    clean_content = redact(report.content)

    disaster, severity = classify(
        clean_content,
        report.type
    )


    # -----------------------------------------------------
    # MESSAGE HASH / DUPLICATE DETECTION
    # -----------------------------------------------------

    raw_hash = (
        report.messageId +
        report.hubId +
        clean_content
    )

    message_hash = hashlib.sha256(
        raw_hash.encode()
    ).hexdigest()


    if any(
        item["hash"] == message_hash
        for item in reports
    ):

        return {
            "status": "duplicate",
            "hash": message_hash
        }


    # -----------------------------------------------------
    # CONFIDENCE
    # -----------------------------------------------------

    if report.type.upper() != "GENERAL":

        confidence = 0.95

    elif disaster != "Unknown":

        confidence = 0.85

    else:

        confidence = 0.60


    # -----------------------------------------------------
    # CREATE INCIDENT
    # -----------------------------------------------------

    item = {

        "hash": message_hash,

        "messageId":
            report.messageId,

        "hubId":
            report.hubId,

        "senderNodeId":
            report.senderNodeId,

        "timestamp":
            report.timestamp,

        "type":
            report.type,

        "content":
            clean_content,

        "disaster":
            disaster,

        "severity":
            severity,

        "confidence":
            confidence,

        "ttl":
            report.ttl,

        "syncedAt":
            datetime.datetime.utcnow().isoformat() + "Z"
    }


    reports.append(item)

    save_reports()

    print(
        f"[SYNC] {item['severity']:<8} {item['disaster']} "
        f"from {report.senderNodeId} ({report.hubId})"
    )


    return {
        "status": "synced",
        "incident": item
    }


# ---------------------------------------------------------
# INCIDENTS
# ---------------------------------------------------------

@app.get("/incidents")
def incidents():

    return list(reversed(reports))

# ---------------------------------------------------------
# DASHBOARD
#
# Serving the dashboard from the API removes the file:// origin
# problem and lets any device on the network open it at
# http://<pc-ip>:8000/
# ---------------------------------------------------------

from fastapi.responses import FileResponse, JSONResponse

DASHBOARD = os.path.join(
    os.path.dirname(BASE_DIR), "dashboard", "index.html"
)


@app.get("/")
def dashboard():

    if os.path.exists(DASHBOARD):
        return FileResponse(DASHBOARD)

    return JSONResponse({
        "service": "AI Disaster Response API",
        "sync": "/sync",
        "incidents": "/incidents",
        "health": "/health"
    })


@app.get("/whereami")
def whereami():
    """Handy from a phone browser to confirm the PC is reachable."""

    return {
        "status": "reachable",
        "host": lan_ip(),
        "other_addresses": [a for a in all_lan_ips() if a != lan_ip()],
        "port": API_PORT,
        "reports": len(reports)
    }
