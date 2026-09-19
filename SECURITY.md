# Security notes

- The TorBox API key is encrypted with Android Keystore on the phone.
- The TorBox API key and temporary TorBox download URLs are never returned to nearby clients.
- Native-client, browser-client, state, library, and stream requests require the current four-digit pairing code.
- WebDAV uses username `carstream` and the current four-digit pairing code as the password.
- WebDAV is read-only. Write and delete operations are not implemented.
- The local-only hotspot has no general Internet route for connected clients.
- Local client traffic uses HTTP inside the private Wi-Fi network rather than application-layer TLS.
- The pairing code is for nearby private-network convenience, not Internet-grade authentication. Stop CarStream when it is not in use.
- Remote browser assets must use HTTPS and match the SHA-256 checksums in the configured manifest.
- Diagnostic exports redact common API keys, tokens, passwords, pairing codes, magnet links, and temporary URLs.
- CarStream Kids stores only the phone address, screen name, pairing code, favorites, and viewing history. It does not store the TorBox API key.
