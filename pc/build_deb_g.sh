#!/bin/bash
set -e

echo "[ClipSync Builder] Starting Debian package compilation process..."

# Define directories
DIR="clipsync_1.0_all"
rm -rf "$DIR"
mkdir -p "$DIR/DEBIAN"
mkdir -p "$DIR/usr/share/clipsync"
mkdir -p "$DIR/etc/xdg/autostart"
mkdir -p "$DIR/usr/share/applications"
mkdir -p "$DIR/usr/bin"

# Write Control file
cat <<EOT > "$DIR/DEBIAN/control"
Package: clipsync
Version: 1.0
Section: utils
Priority: optional
Architecture: all
Depends: python3, python3-pyqt5, xclip
Maintainer: ClipSync Team <clipsync@example.com>
Description: High-performance cross-device P2P clipboard synchronization tool
 This application synchronizes the clipboard of Linux (KDE, Cinnamon)
 and Android phones over the local Wi-Fi secure network automatically.
EOT

# Write post-install script (enables systemd setup)
cat <<EOT > "$DIR/DEBIAN/postinst"
#!/bin/bash
set -e
# Make daemon executable
chmod +x /usr/share/clipsync/clipsync_daemon.py
chmod +x /usr/bin/clipsync
echo "=========================================================="
echo " ClipSync Successfully Installed!"
echo " Daemon is located in: /usr/share/clipsync/clipsync_daemon.py"
echo " Shortcut launcher placed in: /usr/bin/clipsync"
echo " Autostart configured on desktop logins!"
echo " Application menu launcher placed in /usr/share/applications"
echo "=========================================================="
EOT
chmod 755 "$DIR/DEBIAN/postinst"

# Write raw script launcher in /usr/bin
cat <<EOT > "$DIR/usr/bin/clipsync"
#!/bin/bash
python3 /usr/share/clipsync/clipsync_daemon.py "\$@"
EOT

# Populate assets
cp clipsync_daemon.py "$DIR/usr/share/clipsync/clipsync_daemon.py"
cp clipsync.desktop "$DIR/etc/xdg/autostart/clipsync.desktop"
cp clipsync.desktop "$DIR/usr/share/applications/clipsync.desktop"
cp clipsync.service "$DIR/usr/share/clipsync/clipsync.service"

# Build package
echo "[ClipSync Builder] Packing directory structure with dpkg-deb..."
dpkg-deb --build "$DIR"

# Move generated package to clear location
mv clipsync_1.0_all.deb ../clipsync_1.0_all.deb
rm -rf "$DIR"

echo "[ClipSync Builder] Completed successfully! Saved to root directory as 'clipsync_1.0_all.deb'"
