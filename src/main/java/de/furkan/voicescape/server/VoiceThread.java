package de.furkan.voicescape.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;

public class VoiceThread implements Runnable {

  public final Socket currentSocketConnection;
  private final Thread currentThread;
  private final DataInputStream dataIn;
  private final DataOutputStream dataOut;
  public String clientName;
  public MessageThread messageThread;
  public ArrayList<String> mutedPlayers = new ArrayList<>();
  public ArrayList<String> nearPlayers = new ArrayList<>();
  boolean isRunning = true;

  public VoiceThread(Socket conn) {
    this.currentThread = new Thread(this, "VoiceThread-" + conn.getInetAddress().getHostAddress());

    currentSocketConnection = conn;
    try {
      dataIn = new DataInputStream(currentSocketConnection.getInputStream());
      dataOut = new DataOutputStream(currentSocketConnection.getOutputStream());
    } catch (IOException e) {
      stop();
      throw new RuntimeException(e);
    }
    this.currentThread.start();
  }

  public void run() {
    Core.getInstance().voiceSockets.add(this);
    int bytesRead = 0;
    byte[] inBytes = new byte[1096];
    while (bytesRead != -1 && isRunning) {
      try {
        bytesRead = dataIn.read(inBytes, 0, 1096);
        if (bytesRead >= 0) {
          sendToAllClients(inBytes);
        }
      } catch (Exception e) {
        e.printStackTrace();
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
      currentThread.interrupt();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void sendToAllClients(byte[] byteArray) {
    for (VoiceThread socket : Core.getInstance().voiceSockets) {
      try {
        OutputStream tempOut = socket.currentSocketConnection.getOutputStream();
        if (nearPlayers.contains(clientName)
            || (nearPlayers.contains(socket.clientName)
                && !mutedPlayers.contains(socket.clientName)
                && socket.nearPlayers.contains(clientName)
                && Core.getInstance().registeredPlayerSockets.contains(socket.clientName))) {
          tempOut.write(byteArray, 0, byteArray.length);
        }
      } catch (IOException e) {
        e.printStackTrace();
        socket.stop();
      }
    }
  }
}
