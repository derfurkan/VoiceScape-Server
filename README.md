# VoiceScape Server

The Server Software for the OSRS RuneLite VoiceScape proximity chat plugin

## Performance Requirements

For optimal performance this server should be run on a Linux-based operating system.

Linux allows the use of Netty's `Epoll` transport and the `SO_REUSEPORT` socket option. This configuration enables the server to bind multiple UDP sockets to the same port.
## Building and Running

### Prerequisites

- Java 11 or higher
- Gradle

### Build Commands

```bash
./gradlew build
./gradlew shadowJar
```

### Running the Server

You can configure server parameters using `-D` flags. Below is an example command for running the server:

```bash
java -jar -Dport=5555 \
     -Dhandshake_timeout_ms=5000 \
     -Dmax_connections_ip=3 \
     -Dmax_connections=100 \
     -Dsession_timeout_ms=25000 \
     -Dkey_rotation_interval_s=86400 \
     -Dprotocol_version=1 \
     build/libs/server.jar --loopback  
```
(loopback forwards audio back to sender for testing)

## Configuration

Server is configured via system properties. These can be set via command-line arguments (e.g., `-Dkey=value`).

| Property                  | Description                                      |
|:--------------------------|:-------------------------------------------------|
| `port`                    | The port the server binds to                     |
| `max_connections`         | Global limit for concurrent sessions             |
| `max_connections_ip`      | Limit for concurrent sessions per IP address     |
| `handshake_timeout_ms`    | Timeout for the initial connection handshake     |
| `session_timeout_ms`      | Inactivity threshold before a session is removed |
| `key_rotation_interval_s` | Frequency of key rotation in seconds             |
| `protocol_version`        | Current protocol version (must match client)     |
