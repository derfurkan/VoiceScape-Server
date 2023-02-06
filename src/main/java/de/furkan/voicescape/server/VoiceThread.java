package de.furkan.voicescape.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;

public class VoiceThread implements Runnable {

  public final Socket currentSocketConnection;
  private final DataInputStream dataIn;
  private final DataOutputStream dataOut;
  public String clientName;
  public MessageThread messageThread;
  public ArrayList<String> mutedPlayers = new ArrayList<>();
  public ArrayList<String> nearPlayers = new ArrayList<>();
  boolean isRunning = true;

  public VoiceThread(Socket conn) {
    currentSocketConnection = conn;

    try {
      currentSocketConnection.setSendBufferSize(1024);
      currentSocketConnection.setReceiveBufferSize(1024);
      dataIn = new DataInputStream(currentSocketConnection.getInputStream());
      dataOut = new DataOutputStream(currentSocketConnection.getOutputStream());
    } catch (IOException e) {
      stop();
      throw new RuntimeException(e);
    }
  }

  public void run() {
    Core.getInstance().voiceSockets.add(this);
    int bytesRead = 0;
    byte[] inBytes = new byte[1024];
    while (bytesRead != -1 && isRunning) {
      try {
        bytesRead = dataIn.read(inBytes, 0, inBytes.length);
        if (bytesRead >= 0) {
          sendToAllClients(inBytes);
        }
      } catch (Exception e) {
        stop();
      }
    }
  }

  public void stop() {
    try {
      if (!isRunning) return;
      isRunning = false;
      messageThread.stop();
      Core.getInstance().registeredPlayerSockets.remove(clientName);
      Core.getInstance().voiceSockets.remove(this);
      dataIn.close();
      dataOut.close();
      currentSocketConnection.close();
      System.gc();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void sendToAllClients(byte[] byteArray) {
    ArrayList<VoiceThread> voiceThreads =
        (ArrayList<VoiceThread>) Core.getInstance().voiceSockets.clone();
    for (VoiceThread socket : voiceThreads) {
      if (socket.mutedPlayers.contains(clientName) || mutedPlayers.contains(socket.clientName))
        continue;
      try {
        OutputStream tempOut = socket.currentSocketConnection.getOutputStream();
        if (nearPlayers.contains(clientName)
            || (nearPlayers.contains(socket.clientName)
                && socket.nearPlayers.contains(clientName)
                && Core.getInstance().registeredPlayerSockets.contains(socket.clientName))) {
          tempOut.write(byteArray, 0, byteArray.length);
        }
      } catch (IOException e) {
        socket.stop();
      }
    }
  }
}
