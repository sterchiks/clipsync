#!/usr/bin/env python3
import os
import sys
import time
import json
import socket
import threading
import urllib.request
import urllib.parse
import subprocess

PORT_HTTP = 40040
PORT_UDP = 40041

# Shared thread-safe state
phone_ip = ""
is_service_running = True
last_received_text = ""
discovered_peers = set()
history_items = []
lock = threading.Lock()

HISTORY_FILE = os.path.expanduser("~/.config/clipsync/history.json")

# Ensure history save directory exists
os.makedirs(os.path.dirname(HISTORY_FILE), exist_ok=True)

def load_history():
    global history_items
    try:
        if os.path.exists(HISTORY_FILE):
            with open(HISTORY_FILE, "r", encoding="utf-8") as f:
                history_items = json.load(f)
        else:
            history_items = []
    except Exception as e:
        print(f"[ClipSync History] Load error: {e}")
        history_items = []

def save_history():
    try:
        with open(HISTORY_FILE, "w", encoding="utf-8") as f:
            json.dump(history_items, f, ensure_ascii=False, indent=2)
    except Exception as e:
        print(f"[ClipSync History] Save error: {e}")

def add_to_history(text, is_sent, peer):
    global history_items
    item = {
        "text": text,
        "is_sent": is_sent,
        "timestamp": int(time.time() * 1000),
        "peer": peer
    }
    history_items.insert(0, item)
    # limit to 50 items
    if len(history_items) > 50:
        history_items = history_items[:50]
    save_history()

def clear_all_history():
    global history_items
    history_items = []
    save_history()

# Helper to read/write system clipboard directly from CLI fallback
def set_pc_clipboard_xclip(text):
    try:
        process = subprocess.Popen(['xclip', '-selection', 'clipboard', '-i'], stdin=subprocess.PIPE, text=True)
        process.communicate(input=text)
    except Exception as e:
        print(f"[PC Clip Error] Failed writing clipboard with xclip: {e}")

def get_pc_clipboard_xclip():
    try:
        result = subprocess.run(['xclip', '-selection', 'clipboard', '-o'], capture_output=True, text=True, check=True)
        return result.stdout
    except Exception:
        return ""

def get_my_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # Doesn't need to connect simply finds correct route IP
        s.connect(('10.255.255.255', 1))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

def send_to_android(text, ip):
    try:
        data = text.encode('utf-8')
        req = urllib.request.Request(
            f"http://{ip}:{PORT_HTTP}/sync",
            data=data,
            method="POST",
            headers={"Content-Type": "text/plain; charset=utf-8"}
        )
        with urllib.request.urlopen(req, timeout=3) as response:
            return response.status == 200
    except Exception as e:
        print(f"[ClipSync Send Error] Failed sending to Phone ({ip}): {e}")
        return False

# Attempt PyQt5 import to switch to stunning Bento Grid UI
use_gui = False
try:
    from PyQt5.QtWidgets import (QApplication, QWidget, QVBoxLayout, QHBoxLayout, 
                                 QLabel, QPushButton, QTextEdit, QLineEdit, QScrollArea, 
                                 QFrame, QSizePolicy, QGraphicsDropShadowEffect, QLayout)
    from PyQt5.QtCore import Qt, QThread, pyqtSignal, QTimer
    from PyQt5.QtGui import QFont, QColor, QPalette, QBrush, QIcon, QCursor
    if os.environ.get('DISPLAY'):
        use_gui = True
except Exception:
    use_gui = False

# Background socket server Thread
class BackgroundSocketServer(threading.Thread):
    def __init__(self, on_text_received_callback=None):
        super().__init__()
        self.daemon = True
        self.on_text_received = on_text_received_callback

    run_server = True

    def run(self):
        # Start python embedded HTTP server and bind on port 40040
        port = PORT_HTTP
        while True:
            try:
                server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                server_socket.bind(('0.0.0.0', port))
                server_socket.listen(10)
                break
            except Exception as e:
                print(f"[ClipSync Server] Bind failed on port {port}: {e}. Retrying in 5 seconds...")
                time.sleep(5)

        print(f"[ClipSync Server] Active on Port {port}...")
        while self.run_server:
            try:
                client_socket, client_address = server_socket.accept()
                threading.Thread(target=self.handle_client, args=(client_socket, client_address), daemon=True).start()
            except Exception:
                break

    def handle_client(self, client_socket, client_address):
        global phone_ip, last_received_text
        try:
            # Parse HTTP Request Content
            req_data = b""
            while True:
                chunk = client_socket.recv(4096)
                if not chunk:
                    break
                req_data += chunk
                if b"\r\n\r\n" in req_data:
                    # check content-length
                    headers, _, body = req_data.partition(b"\r\n\r\n")
                    content_length = 0
                    for line in headers.decode('utf-8', errors='ignore').split("\r\n"):
                        if line.lower().startswith("content-length:"):
                            content_length = int(line.split(":")[1].strip())
                            break
                    if len(body) >= content_length:
                        break

            if not req_data:
                client_socket.close()
                return

            headers_part, _, body_part = req_data.partition(b"\r\n\r\n")
            headers_text = headers_part.decode('utf-8', errors='ignore')
            lines = headers_text.split("\r\n")
            if not lines:
                client_socket.close()
                return

            req_line = lines[0]
            parts = req_line.split(" ")
            if len(parts) >= 2:
                method, path = parts[0], parts[1]
                if method == "POST" and path == "/sync":
                    content_len = 0
                    for line in lines:
                        if line.lower().startswith("content-length:"):
                            content_len = int(line.split(":")[1].strip())
                            break
                    
                    text = body_part[:content_len].decode('utf-8', errors='ignore')
                    
                    if text:
                        with lock:
                            phone_ip = client_address[0]
                            last_received_text = text
                            add_to_history(text, is_sent=False, peer=phone_ip)

                        print(f"\n[ClipSync] Received text block from Phone ({client_address[0]}): {text[:60]}...")
                        
                        if self.on_text_received:
                            self.on_text_received(text, client_address[0])
                        else:
                            # Direct head-less xclip handling
                            set_pc_clipboard_xclip(text)

                        # Respond OK
                        response_body = "OK"
                        resp_bytes = response_body.encode('utf-8')
                        response = f"HTTP/1.1 200 OK\r\nContent-Length: {len(resp_bytes)}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes
                        client_socket.sendall(response)
                        client_socket.close()
                        return
                
                elif method == "GET" and path == "/status":
                    resp_bytes = "PC Daemon OK".encode('utf-8')
                    response = f"HTTP/1.1 200 OK\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n".encode('utf-8') + resp_bytes
                    client_socket.sendall(response)
                    client_socket.close()
                    return

            # Default fallback 404
            client_socket.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            client_socket.close()
        except Exception as e:
            print(f"[ClipSync Client Handle Error] {e}")


# Background UDP Discovery Listener and Responder
class UdpServerThread(threading.Thread):
    def __init__(self, on_peer_discovered_callback=None):
        super().__init__()
        self.daemon = True
        self.on_peer_discovered = on_peer_discovered_callback

    def run(self):
        global phone_ip, discovered_peers
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.bind(('0.0.0.0', PORT_UDP))
        except Exception as e:
            print(f"[ClipSync UDP] Could not bind on port {PORT_UDP}. Auto Discovery disabled: {e}")
            return

        while True:
            try:
                data, addr = sock.recvfrom(1024)
                message = data.decode('utf-8', errors='ignore')
                if message.startswith("CLIPS_DISCOVERY_PONG:PHONE:") or message.startswith("CLIPS_DISCOVERY_PING:PHONE:"):
                    parts = message.split(":")
                    if len(parts) >= 3:
                        sender_ip = parts[2]
                        with lock:
                            discovered_peers.add(sender_ip)
                            if not phone_ip:
                                phone_ip = sender_ip
                        
                        if self.on_peer_discovered:
                            self.on_peer_discovered(sender_ip)
            except Exception:
                break


class UdpBroadcasterThread(threading.Thread):
    def run(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        my_ip = get_my_ip()
        message = f"CLIPS_DISCOVERY_PING:PC:{my_ip}".encode('utf-8')
        broadcast_address = ("255.255.255.255", PORT_UDP)
        
        while True:
            try:
                sock.sendto(message, broadcast_address)
            except Exception:
                pass
            time.sleep(5)


# PRETTY CUSTOM COMPONENT: Stylish Switch / Toggle
if use_gui:
    class ModernToggle(QPushButton):
        def __init__(self, parent=None):
            super().__init__(parent)
            self.setCheckable(True)
            self.setCursor(QCursor(Qt.PointingHandCursor))
            self.setFixedHeight(24)
            self.setFixedWidth(50)
            self.setStyleSheet("""
                QPushButton {
                    background-color: #C4C4C4;
                    border: 1px solid #B0B0B0;
                    border-radius: 12px;
                    text-align: left;
                    padding-left: 2px;
                }
                QPushButton:checked {
                    background-color: #6750A4;
                    border: 1px solid #523E8A;
                    text-align: right;
                    padding-right: 2px;
                }
            """)
            self.indicator = QLabel("●", self)
            self.indicator.setFont(QFont("Arial", 16))
            self.indicator.setAttribute(Qt.WA_TransparentForMouseEvents)
            self.update_indicator()
            self.clicked.connect(self.update_indicator)

        def update_indicator(self):
            if self.isChecked():
                self.indicator.setStyleSheet("color: white;")
                self.indicator.move(30, -2)
            else:
                self.indicator.setStyleSheet("color: white;")
                self.indicator.move(4, -2)


    class ClipSyncGUI(QWidget):
        text_signal = pyqtSignal(str, str)
        peer_signal = pyqtSignal(str)

        def __init__(self):
            super().__init__()
            load_history()
            self.init_ui()

            # Wire background threads to PyQt Event-loop safe Signals
            self.text_signal.connect(self.on_text_received_main_thread)
            self.peer_signal.connect(self.on_peer_discovered_main_thread)

            # Start Servers
            self.server_thread = BackgroundSocketServer(on_text_received_callback=self.trigger_text_signal)
            self.server_thread.start()

            self.udp_listener = UdpServerThread(on_peer_discovered_callback=self.trigger_peer_signal)
            self.udp_listener.start()

            self.udp_broadcaster = UdpBroadcasterThread()
            self.udp_broadcaster.daemon = True
            self.udp_broadcaster.start()

            # Active native system clipboard hooks
            self.sys_clipboard = QApplication.clipboard()
            self.sys_clipboard.dataChanged.connect(self.on_local_pc_clipboard_changed)
            self.ignore_next_clipboard_change = False

            # Peer IP refresh scheduler
            self.ui_timer = QTimer(self)
            self.ui_timer.timeout.connect(self.refresh_ui_elements)
            self.ui_timer.start(2000)

        def trigger_text_signal(self, text, ip):
            self.text_signal.emit(text, ip)

        def trigger_peer_signal(self, ip):
            self.peer_signal.emit(ip)

        def init_ui(self):
            # Styling constants matching Bento Material layout perfectly
            self.setWindowTitle("ClipSync Desktop Node")
            self.setMinimumSize(480, 680)
            self.setMaximumWidth(520)

            # Palette styles
            self.setStyleSheet("""
                QWidget {
                    background-color: #FEF7FF;
                    color: #1D1B20;
                    font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
                }
                QLabel {
                    background-color: transparent;
                }
            """)

            main_layout = QVBoxLayout()
            main_layout.setContentsMargins(20, 20, 20, 20)
            main_layout.setSpacing(14)

            # SECTION 1: HEADER PANEL
            header_layout = QHBoxLayout()
            
            title_vbox = QVBoxLayout()
            title_lbl = QLabel("ClipSync")
            title_lbl.setFont(QFont("Arial", 22, QFont.Bold))
            title_lbl.setStyleSheet("letter-spacing: -1px; color: #1D1B20;")
            
            status_hbox = QHBoxLayout()
            status_hbox.setSpacing(4)
            self.indicator_dot = QLabel("●")
            self.indicator_dot.setStyleSheet("color: #48B14C; font-size: 14px;")
            self.status_text = QLabel("Service Active • 24/7")
            self.status_text.setFont(QFont("Arial", 9, QFont.Bold))
            self.status_text.setStyleSheet("color: #49454F; letter-spacing: 0.5px; text-transform: uppercase;")
            status_hbox.addWidget(self.indicator_dot)
            status_hbox.addWidget(self.status_text)
            status_hbox.addStretch(1)

            title_vbox.addWidget(title_lbl)
            title_vbox.addLayout(status_hbox)

            icon_badge = QLabel("✨")
            icon_badge.setFont(QFont("Arial", 22))
            icon_badge.setFixedSize(48, 48)
            icon_badge.setAlignment(Qt.AlignCenter)
            icon_badge.setStyleSheet("background-color: #E8DEF8; border-radius: 24px;")

            header_layout.addLayout(title_vbox)
            header_layout.addWidget(icon_badge)
            main_layout.addLayout(header_layout)

            # SECTION 2: CURRENT SYNCED BUFFER (BENTO BLK 1)
            buffer_card = QFrame()
            buffer_card.setStyleSheet("background-color: #F3EDF7; border: 1px solid #E6E0E9; border-radius: 28px;")
            buffer_vbox = QVBoxLayout(buffer_card)
            buffer_vbox.setContentsMargins(18, 18, 18, 18)
            buffer_vbox.setSpacing(10)

            buffer_header = QHBoxLayout()
            buffer_lbl = QLabel("📋 CURRENT SYNCED BUFFER")
            buffer_lbl.setFont(QFont("Arial", 9, QFont.Bold))
            buffer_lbl.setStyleSheet("color: #6750A4; letter-spacing: 0.5px;")
            
            self.time_lbl = QLabel("Live Sync Active")
            self.time_lbl.setFont(QFont("Courier", 8))
            self.time_lbl.setStyleSheet("color: #49454F; font-weight: bold;")
            
            buffer_header.addWidget(buffer_lbl)
            buffer_header.addStretch(1)
            buffer_header.addWidget(self.time_lbl)
            buffer_vbox.addLayout(buffer_header)

            # Inner Text Input field
            self.buffer_text = QTextEdit()
            self.buffer_text.setFont(QFont("Arial", 11, QFont.Medium))
            self.buffer_text.setPlaceholderText("No synchronized text fragments yet. Type or paste here, then tap push to sync to phone.")
            self.buffer_text.setStyleSheet("""
                QTextEdit {
                    background-color: rgba(255, 255, 255, 0.75);
                    border: none;
                    border-radius: 16px;
                    padding: 12px;
                    color: #1D1B20;
                }
            """)
            buffer_vbox.addWidget(self.buffer_text)

            # Trigger Actions Row
            actions_hbox = QHBoxLayout()
            self.push_btn = QPushButton("Push to Phone")
            self.push_btn.setFont(QFont("Arial", 10, QFont.Bold))
            self.push_btn.setMinimumHeight(42)
            self.push_btn.setStyleSheet("""
                QPushButton {
                    background-color: #6750A4;
                    color: white;
                    border-radius: 14px;
                    font-weight: bold;
                }
                QPushButton:hover {
                    background-color: #523E8A;
                }
                QPushButton:pressed {
                    background-color: #3B2A63;
                }
            """)
            self.push_btn.clicked.connect(self.push_clipboard_to_phone)

            self.copy_btn = QPushButton("Copy Local")
            self.copy_btn.setFont(QFont("Arial", 10, QFont.Bold))
            self.copy_btn.setFixedSize(110, 42)
            self.copy_btn.setStyleSheet("""
                QPushButton {
                    background-color: #E8DEF8;
                    color: #1D192B;
                    border-radius: 14px;
                    font-weight: bold;
                }
                QPushButton:hover {
                    background-color: #D3CADF;
                }
            """)
            self.copy_btn.clicked.connect(self.copy_buffer_to_pc_native_clip)

            actions_hbox.addWidget(self.push_btn)
            actions_hbox.addWidget(self.copy_btn)
            buffer_vbox.addLayout(actions_hbox)

            main_layout.addWidget(buffer_card)

            # SECTION 3: DOUBLE COLUMN GRID ROW
            grid_layout = QHBoxLayout()
            grid_layout.setSpacing(10)

            # Col L: Phone Client Config
            phone_card = QFrame()
            phone_card.setStyleSheet("background-color: #F3EDF7; border: 1px solid #E6E0E9; border-radius: 24px;")
            phone_vbox = QVBoxLayout(phone_card)
            phone_vbox.setContentsMargins(14, 14, 14, 14)
            phone_vbox.setSpacing(6)

            phone_header = QHBoxLayout()
            phone_icon = QLabel("📱")
            phone_icon.setFont(QFont("Arial", 12))
            phone_title = QLabel("PHONE CLIPS IP")
            phone_title.setFont(QFont("Arial", 8, QFont.Bold))
            phone_title.setStyleSheet("color: #6750A4;")
            phone_header.addWidget(phone_icon)
            phone_header.addWidget(phone_title)
            phone_header.addStretch(1)
            phone_vbox.addLayout(phone_header)

            self.phone_ip_input = QLineEdit()
            self.phone_ip_input.setFont(QFont("Courier", 10, QFont.Bold))
            self.phone_ip_input.setPlaceholderText("Auto-pairing...")
            self.phone_ip_input.setStyleSheet("""
                QLineEdit {
                    background-color: white;
                    border: 1px solid #E6E0E9;
                    border-radius: 10px;
                    padding: 6px;
                    color: #1D1B20;
                }
            """)
            self.phone_ip_input.textChanged.connect(self.update_phone_ip_manually)
            phone_vbox.addWidget(self.phone_ip_input)

            self.phone_device_lbl = QLabel("Samsung Galaxy A50\n(Android 11)")
            self.phone_device_lbl.setFont(QFont("Arial", 8))
            self.phone_device_lbl.setStyleSheet("color: #49454F; line-height: 12px;")
            phone_vbox.addWidget(self.phone_device_lbl)

            # Col R: PC Network Status Node
            pc_card = QFrame()
            pc_card.setStyleSheet("background-color: #F3EDF7; border: 1px solid #E6E0E9; border-radius: 24px;")
            pc_vbox = QVBoxLayout(pc_card)
            pc_vbox.setContentsMargins(14, 14, 14, 14)
            pc_vbox.setSpacing(6)

            pc_header = QHBoxLayout()
            pc_icon = QLabel("💻")
            pc_icon.setFont(QFont("Arial", 12))
            pc_title = QLabel("PC NETWORK NODE")
            pc_title.setFont(QFont("Arial", 8, QFont.Bold))
            pc_title.setStyleSheet("color: #6750A4;")
            pc_header.addWidget(pc_icon)
            pc_header.addWidget(pc_title)
            pc_header.addStretch(1)
            pc_vbox.addLayout(pc_header)

            my_ip = get_my_ip()
            self.pc_ip_text = QLabel(f"{my_ip}")
            self.pc_ip_text.setFont(QFont("Courier", 11, QFont.Bold))
            self.pc_ip_text.setStyleSheet("color: #1D1B20;")
            
            pc_subtitle = QLabel(f"Port 40040 Socket\nLinux Mint (KDE)")
            pc_subtitle.setFont(QFont("Arial", 8))
            pc_subtitle.setStyleSheet("color: #49454F;")

            pc_vbox.addWidget(self.pc_ip_text)
            pc_vbox.addWidget(pc_subtitle)

            grid_layout.addWidget(phone_card)
            grid_layout.addWidget(pc_card)
            main_layout.addLayout(grid_layout)

            # SECTION 4: QUICK AUTOSYNC SLIDER FRAME
            autocheck_card = QFrame()
            autocheck_card.setStyleSheet("background-color: #F3EDF7; border: 1px solid #E6E0E9; border-radius: 24px;")
            autocheck_hbox = QHBoxLayout(autocheck_card)
            autocheck_hbox.setContentsMargins(16, 12, 16, 12)

            text_group = QVBoxLayout()
            sync_lbl = QLabel("Auto-Sync Daemon")
            sync_lbl.setFont(QFont("Arial", 10, QFont.Bold))
            sync_desc = QLabel("Bidirectional background tracking active")
            sync_desc.setFont(QFont("Arial", 8))
            sync_desc.setStyleSheet("color: #49454F;")
            text_group.addWidget(sync_lbl)
            text_group.addWidget(sync_desc)

            self.auto_sync_switch = ModernToggle()
            self.auto_sync_switch.setChecked(True)
            self.auto_sync_switch.clicked.connect(self.toggle_local_service_active)

            autocheck_hbox.addLayout(text_group)
            autocheck_hbox.addStretch(1)
            autocheck_hbox.addWidget(self.auto_sync_switch)
            
            main_layout.addWidget(autocheck_card)

            # SECTION 5: SYNC HISTORY
            history_title_row = QHBoxLayout()
            history_lbl = QLabel("Sync History Logs")
            history_lbl.setFont(QFont("Arial", 12, QFont.Bold))
            
            self.history_badge = QLabel("0")
            self.history_badge.setAlignment(Qt.AlignCenter)
            self.history_badge.setFont(QFont("Arial", 9, QFont.Bold))
            self.history_badge.setFixedSize(22, 18)
            self.history_badge.setStyleSheet("background-color: #1D1B20; color: white; border-radius: 9px;")

            clear_btn = QPushButton("Clear All")
            clear_btn.setFont(QFont("Arial", 8, QFont.Bold))
            clear_btn.setFlat(True)
            clear_btn.setCursor(QCursor(Qt.PointingHandCursor))
            clear_btn.setStyleSheet("color: #B3261E; border: none; font-weight: bold;")
            clear_btn.clicked.connect(self.clear_history_log_ui)

            history_title_row.addWidget(history_lbl)
            history_title_row.addWidget(self.history_badge)
            history_title_row.addStretch(1)
            history_title_row.addWidget(clear_btn)
            main_layout.addLayout(history_title_row)

            # History scroll container
            scroll_area = QScrollArea()
            scroll_area.setWidgetResizable(True)
            scroll_area.setFrameShape(QFrame.NoFrame)
            scroll_area.setStyleSheet("background-color: transparent;")

            self.scroll_widget = QWidget()
            self.scroll_widget.setStyleSheet("background-color: transparent;")
            self.history_layout = QVBoxLayout(self.scroll_widget)
            self.history_layout.setContentsMargins(0, 0, 0, 0)
            self.history_layout.setSpacing(8)
            self.history_layout.setAlignment(Qt.AlignTop)

            scroll_area.setWidget(self.scroll_widget)
            main_layout.addWidget(scroll_area, 1) # Set weight as 1 to allow expansion

            self.setLayout(main_layout)
            self.refresh_history_list()

        def toggle_local_service_active(self):
            global is_service_running
            is_service_running = self.auto_sync_switch.isChecked()
            if is_service_running:
                self.indicator_dot.setStyleSheet("color: #48B14C; font-size: 14px;")
                self.status_text.setText("Service Active • 24/7")
            else:
                self.indicator_dot.setStyleSheet("color: #B3261E; font-size: 14px;")
                self.status_text.setText("Service Stopped")

        def on_text_received_main_thread(self, text, ip):
            # Safe GUI execution callback thread mapping
            if not is_service_running:
                return

            self.ignore_next_clipboard_change = True
            self.sys_clipboard.setText(text)
            self.buffer_text.setText(text)
            
            if not self.phone_ip_input.text():
                self.phone_ip_input.setText(ip)

            self.refresh_history_list()

            # Pop KDE notification bubble automatically using standard system command
            try:
                subprocess.run(["notify-send", "ClipSync P2P Shared Board", f"Received: {text[:45]}..."], check=False)
            except Exception:
                pass

        def on_peer_discovered_main_thread(self, ip):
            # Update matching manual lists
            current_typed = self.phone_ip_input.text()
            if not current_typed:
                self.phone_ip_input.setText(ip)
                global phone_ip
                phone_ip = ip

        def on_local_pc_clipboard_changed(self):
            global phone_ip
            if not is_service_running:
                return
            if self.ignore_next_clipboard_change:
                self.ignore_next_clipboard_change = False
                return

            text = self.sys_clipboard.text()
            if text and text != last_received_text:
                self.buffer_text.setText(text)
                
                # Try to push automatically to the active recorded phone port
                target_ip = self.phone_ip_input.text().strip()
                if not target_ip:
                    target_ip = phone_ip

                if target_ip:
                    success = send_to_android(text, target_ip)
                    if success:
                        add_to_history(text, is_sent=True, peer=target_ip)
                        self.refresh_history_list()
                    else:
                        print(f"[ClipSync] Autopush failed target offline: {target_ip}")

        def push_clipboard_to_phone(self):
            text = self.buffer_text.toPlainText().strip()
            target_ip = self.phone_ip_input.text().strip()
            if text and target_ip:
                self.push_btn.setText("Pushing...")
                self.push_btn.setEnabled(False)
                
                # Perform post thread safely or simply execute
                success = send_to_android(text, target_ip)
                if success:
                    add_to_history(text, is_sent=True, peer=target_ip)
                    self.refresh_history_list()
                    self.push_btn.setText("Sent Successfully! ✓")
                else:
                    self.push_btn.setText("Send Failed ❌")

                QTimer.singleShot(1500, lambda: (
                    self.push_btn.setText("Push to Phone"),
                    self.push_btn.setEnabled(True)
                ))

        def copy_buffer_to_pc_native_clip(self):
            text = self.buffer_text.toPlainText()
            if text:
                self.ignore_next_clipboard_change = True
                self.sys_clipboard.setText(text)
                self.copy_btn.setText("Copied!")
                QTimer.singleShot(1000, lambda: self.copy_btn.setText("Copy Local"))

        def update_phone_ip_manually(self, text):
            global phone_ip
            phone_ip = text.strip()

        def refresh_ui_elements(self):
            # Pull detected state down to display card
            my_ip = get_my_ip()
            self.pc_ip_text.setText(my_ip)

        def clear_history_log_ui(self):
            clear_all_history()
            self.refresh_history_list()

        def refresh_history_list(self):
            # Clear old elements
            while self.history_layout.count():
                item = self.history_layout.takeAt(0)
                widget = item.widget()
                if widget:
                    widget.deleteLater()

            # Refresh badge indicator total clips count
            self.history_badge.setText(str(len(history_items)))

            if not history_items:
                empty_lbl = QLabel("No synchronization activities logged yet.")
                empty_lbl.setFont(QFont("Arial", 9))
                empty_lbl.setStyleSheet("color: #49454F; padding: 10px;")
                self.history_layout.addWidget(empty_lbl)
                return

            for i, item in enumerate(history_items):
                card = QFrame()
                card.setStyleSheet("""
                    QFrame {
                        background-color: rgba(243, 237, 247, 0.5);
                        border: 1px solid rgba(230, 224, 233, 0.5);
                        border-radius: 14px;
                    }
                """)
                card_layout = QHBoxLayout(card)
                card_layout.setContentsMargins(10, 10, 10, 10)

                # Icon indicator
                dir_icon = QLabel("⬆" if item["is_sent"] else "⬇")
                dir_icon.setAlignment(Qt.AlignCenter)
                dir_icon.setFont(QFont("Arial", 11, QFont.Bold))
                dir_icon.setFixedSize(28, 28)
                if item["is_sent"]:
                    dir_icon.setStyleSheet("background-color: rgba(103, 80, 164, 0.15); color: #6750A4; border-radius: 14px;")
                else:
                    dir_icon.setStyleSheet("background-color: rgba(232, 222, 248, 1); color: #1D1B20; border-radius: 14px;")

                text_vbox = QVBoxLayout()
                text_vbox.setSpacing(2)
                
                info_hbox = QHBoxLayout()
                info_lbl = QLabel("Sent to Phone" if item["is_sent"] else "Received from Phone")
                info_lbl.setFont(QFont("Arial", 8, QFont.Bold))
                info_lbl.setStyleSheet("color: #6750A4;" if item["is_sent"] else "color: #1D1B20;")
                
                # Format time HH:mm
                ts = item["timestamp"] / 1000
                time_str = time.strftime("%H:%M:%S", time.localtime(ts))
                time_lbl = QLabel(time_str)
                time_lbl.setFont(QFont("Arial", 7))
                time_lbl.setStyleSheet("color: #49454F; opacity: 0.7;")

                info_hbox.addWidget(info_lbl)
                info_hbox.addStretch(1)
                info_hbox.addWidget(time_lbl)
                text_vbox.addLayout(info_hbox)

                content_preview = QLabel(item["text"])
                content_preview.setFont(QFont("Arial", 9, QFont.Medium))
                content_preview.setStyleSheet("color: #1D1B20;")
                # Elide long lines manually inside label
                raw_text = item["text"].strip().replace("\n", " ")
                if len(raw_text) > 42:
                    raw_text = raw_text[:40] + "..."
                content_preview.setText(raw_text)

                text_vbox.addWidget(content_preview)

                card_layout.addWidget(dir_icon)
                card_layout.addLayout(text_vbox, 1)

                # Wire interactive click trigger to load selected history text
                card.setCursor(QCursor(Qt.PointingHandCursor))
                # Store text block closure securely
                card.mousePressEvent = lambda event, txt=item["text"]: self.load_text_from_history(txt)

                self.history_layout.addWidget(card)

        def load_text_from_history(self, text):
            self.buffer_text.setText(text)
            self.sys_clipboard.setText(text)


else:
    # CLI FALLBACK MODE
    def monitor_and_sync_clipboard_cli():
        global phone_ip, last_received_text
        last_clip = get_pc_clipboard_xclip()
        print("[ClipSync CLI Mode] Monitoring keyboard / clipboard selection inputs with xclip active...")
        while True:
            try:
                current_clip = get_pc_clipboard_xclip()
                if current_clip and current_clip != last_clip:
                    last_clip = current_clip
                    target = phone_ip
                    if target:
                        print(f"[ClipSync CLI] Pushing clipboard modified changes to paired device ({target})...")
                        send_to_android(current_clip, target)
                        add_to_history(current_clip, is_sent=True, peer=target)
                    else:
                        print("[ClipSync CLI] Clipboard modified, but no phone paired yet. Enable ClipSync on phone first.")
                time.sleep(1.8)
            except KeyboardInterrupt:
                break
            except Exception as e:
                print(f"[ClipSync Monitor Error] {e}")
                time.sleep(3)


def main():
    global use_gui
    print("=============================================================")
    print("  CLIPSYNC CROSS-DEVICE LOCAL COOPERATION DAEMON CLIENT")
    print("=============================================================")
    
    # Check if xclip is installed
    try:
        subprocess.run(['xclip', '-version'], capture_output=True)
    except FileNotFoundError:
        print("[ClipSync ERROR] 'xclip' utility is not found!")
        print("Please install 'xclip' on Linux Mint by executing:")
        print("   sudo apt update && sudo apt install -y xclip")
        print("=============================================================")
        sys.exit(1)

    load_history()

    # Allow forcing headless/CLI mode via command-line arguments
    if "--headless" in sys.argv or "--cli" in sys.argv or "-h" in sys.argv:
        use_gui = False

    # If GUI is supported and enabled, start the PyQt application
    if use_gui:
        print("[ClipSync Initiator] Display screen found. Starting beautiful Bento PyQt5 GUI...")
        app = QApplication(sys.argv)
        
        # Set Application Theme palette to light slate matching android aesthetics
        app.setStyle("Fusion")
        
        gui = ClipSyncGUI()
        gui.show()
        sys.exit(app.exec_())
    else:
        # head-less CLI daemon fallback execution
        print("[ClipSync Initiator] Headless session or PyQt5 not present. Starting CLI background services...")
        local_ip = get_my_ip()
        print(f"[ClipSync Client IP] {local_ip}")

        # Start listening threads
        server = BackgroundSocketServer()
        server.start()
        
        udp_listener = UdpServerThread()
        udp_listener.start()

        broadcaster = UdpBroadcasterThread()
        broadcaster.daemon = True
        broadcaster.start()

        # Monitor clipboard manually via headless loop
        monitor_and_sync_clipboard_cli()


if __name__ == "__main__":
    main()
