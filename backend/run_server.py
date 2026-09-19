"""
Start the AI Disaster Response backend so that PHONES can reach it.

`uvicorn main:app --reload` binds to 127.0.0.1, which is reachable only
from this PC. That is why reports from a real phone never arrive. This
script binds to every interface and prints the exact address to enter
in the app.
"""

import socket
import uvicorn


def lan_ip():

    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
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


if __name__ == "__main__":

    ip = lan_ip()
    port = 8000

    print("=" * 58)
    print("  AI DISASTER RESPONSE - MAIN SYSTEM")
    print("=" * 58)
    print(f"  Dashboard (this PC) : http://127.0.0.1:{port}/")
    print(f"  Dashboard (phone)   : http://{ip}:{port}/")
    print(f"  Sync endpoint       : http://{ip}:{port}/sync")

    others = [a for a in all_lan_ips() if a != ip]
    if others:
        print()
        print("  This PC also has other network addresses:")
        for addr in others:
            print(f"    http://{addr}:{port}/sync")
        print("  If the address above doesn't work (common with a VPN,")
        print("  Docker, Hyper-V, or WSL installed), try one of these --")
        print("  one of them is your real Wi-Fi address.")
    print()
    print("  The app finds this automatically. If it does not,")
    print("  enter the sync endpoint above under")
    print("  Settings / Privacy -> Main system address.")
    print()
    print("  Windows: if the phone cannot connect, allow the port")
    print("  once from an ADMIN PowerShell:")
    print(f'    New-NetFirewallRule -DisplayName "Disaster {port}" '
          f'-Direction Inbound -Protocol TCP -LocalPort {port} -Action Allow')
    print(f'    New-NetFirewallRule -DisplayName "Disaster 8765" '
          f'-Direction Inbound -Protocol UDP -LocalPort 8765 -Action Allow')
    print("=" * 58)

    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=False)
