package de.furkan.voicescape.server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class VoiceServerThread implements Runnable {

  public DatagramSocket voiceServer;

  public VoiceServerThread() {
    Thread thread = new Thread(this, "VoiceServerThread");
    thread.start();
  }

  @Override
  public void run() {
    try {
      voiceServer = new DatagramSocket(Core.getInstance().VOICE_SERVER_PORT);
      while (true) {

        DatagramPacket packet = new DatagramPacket(new byte[8192], 8192);
        voiceServer.receive(packet);
        VoicePacket rtpPacket = new VoicePacket(packet.getData(), packet.getLength());
        int payload_length = rtpPacket.getpayload_length();
        byte[] payload = new byte[payload_length];
        rtpPacket.getpayload(payload);

        Core.getInstance()
            .clientThreads
            .forEach(
                clientThread -> {
                  if (clientThread
                          .currentConnection
                          .getInetAddress()
                          .getHostAddress()
                          .equals(packet.getAddress().getHostAddress())
                      && clientThread.voicePort == packet.getPort()) {
                    clientThread.lastVoicePacket = System.currentTimeMillis();
                    Core.getInstance()
                        .clientThreads
                        .forEach(
                            otherClients -> {
                              if (otherClients.clientName.equals(clientThread.clientName)) return;
                              if (otherClients.nearPlayers.contains(clientThread.clientName)
                                  && clientThread.nearPlayers.contains(otherClients.clientName)) {
                                otherClients.sendBytesToVoiceClient(payload, payload_length);
                              }
                            });
                  }
                });
      }

    } catch (Exception ignore) {

    }
  }
}
