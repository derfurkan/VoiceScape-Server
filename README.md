# VoiceScape Server

The Server Software for the OSRS RuneLite VoiceScape proximity chat plugin

## Performance Requirements

For optimal performance this server should be run on a Linux-based operating system.

Linux allows the use of Netty's `Epoll` transport and the `SO_REUSEPORT` socket option. This configuration enables the server to bind multiple UDP sockets to the same port.
## Building and Running

### Prerequisites

- Java 21 or higher
- Gradle

### Build Commands

```bash
./gradlew build
./gradlew shadowJar
```

### Running the Server

Below is an example command for running the server:

```bash
java -jar -Dport=5555 \
     -Dhandshake_timeout_ms=5000 \
     -Dmax_connections_ip=3 \
     -Dmax_connections=100 \
     -Dmax_client_bps=1000000 \
     -Dmax_pacer_queue=4 \
     -Dmax_pacer_wait_ms=20 \
     -Dsession_timeout_ms=25000 \
     -Dkey_rotation_interval_s=86400 \
     -Dprotocol_version=2 \
     build/libs/voicescape-server.jar --loopback
```
(loopback forwards audio back to sender for testing)

## Configuration

Server is configured via system properties. These can be set via command-line arguments (e.g., `-Dkey=value`).

| Property                   | Description                                                    |
|:---------------------------|:---------------------------------------------------------------|
| `port`                     | The port the server binds to                                   |
| `max_connections`          | Global limit for concurrent sessions                           |
| `max_connections_ip`       | Limit for concurrent sessions per IP address                   |
| `max_client_bps`           | Bandwidth bps limit for each client                            |
| `max_pacer_queue`          | Max amount of packets which can be queued                      |
| `max_pacer_wait_ms`        | Amount of ms the pacer should wait before sending a new packet |
| `handshake_timeout_ms`     | Timeout for the initial connection handshake                   |
| `session_timeout_ms`       | Inactivity threshold before a session is removed               |
| `key_rotation_interval_s`  | Frequency of key rotation in seconds                           |
| `protocol_version`         | Current protocol version (must match client)                   |

## Protocol v2 migration and security

Deploy the v2 client and server together; v1 is incompatible. TCP requires TLS with a certificate trusted by the client JVM and matching the configured hostname. Start the server with `-Dtls_cert=/path/fullchain.pem -Dtls_key=/path/private-key.pem` before `-jar`. There is no insecure fallback. Session resumption is disabled. Audio uses authenticated AES-GCM with random packet nonces and fixed 24 kHz / 16-bit mono PCM (960 bytes per 20 ms frame).

Player-name hashes are pseudonyms, **not verified account identities**: every connected client receives the daily HMAC key and can claim an unoccupied name. Neither proximity nor ownership of an OSRS account can be securely established from client-supplied hashes. Do not treat this service as a private or authenticated conversation channel. A future account-verification service is needed for that guarantee. The relay can decrypt audio and can identify guessed player names; this is not end-to-end encryption.

