FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x gradlew && ./gradlew clean build -x check -x test -Pproduction

EXPOSE 5555

CMD ["java", "-Dport=5555", "-Dmax_frame_length=1500", "-Dhandshake_timeout_ms=20000", "-Dmax_connections_ip=30000", "-Dmax_connections=1000", "-Dmax_audio_packets_s=70", "-Dmax_hash_packets_s=5", "-Dmax_bps=64000", "-Dmax_forward_clients=50", "-Dforward_client_timeout=2000", "-Dmax_audio_packet_bytes=200", "-Dmax_nearby_hashes=200", "-Dmax_hash_packets_length=256", "-Dmax_sessionid_length=16", "-Dsession_timeout_ms=20000", "-Dkey_rotation_interval_ms=43200000", "-Dprotocol_version=1", "-jar", "build/libs/server.jar", "--loopback"]
