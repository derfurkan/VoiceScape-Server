package de.furkan.voicescape.server;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ThreadPoolExecutor;

public class ClientThread implements Runnable {

  public final PrintWriter out;
  public final Socket currentConnection;
  private final BufferedReader in;
  private final ThreadPoolExecutor threadPoolExecutor;
  private final DatagramSocket voiceServer;
  public int voicePort;
  public String clientName;
  public ArrayList<String> nearPlayers = new ArrayList<>();
  public ArrayList<String> mutedPlayers = new ArrayList<>();
  private boolean isRunning = true;
  private long lastMessage, messageCount, flags;
  private long localSequenceNumber = 0;

  public ClientThread(
      Socket currentConnection, ThreadPoolExecutor threadPoolExecutor, DatagramSocket voiceServer) {
    this.voiceServer = voiceServer;
    this.threadPoolExecutor = threadPoolExecutor;
    this.currentConnection = currentConnection;

    try {
      out = new PrintWriter(currentConnection.getOutputStream(), true);
      in = new BufferedReader(new InputStreamReader(currentConnection.getInputStream()));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void run() {
    try {

      String inputLine;
      while ((inputLine = in.readLine()) != null && isRunning) {
        messageCount++;
        if (lastMessage != 0L
            && System.currentTimeMillis() - lastMessage < Core.getInstance().MIN_MESSAGE_TIMEOUT_MS
            && messageCount >= 5) {
          flags++;
          new Timer()
              .schedule(
                  new TimerTask() {
                    @Override
                    public void run() {
                      flags--;
                    }
                  },
                  Core.getInstance().FLAG_REMOVE_TIMEOUT_MS);
          if (flags >= 3) {
            System.out.println(
                "["
                    + clientName
                    + "/"
                    + currentConnection.getInetAddress().getHostAddress()
                    + "] Message spamming detected, disconnecting and blacklisting IP for "
                    + Core.getInstance().MESSAGE_SPAM_BLACKLIST_BAN_MS / 1000
                    + " seconds.");
            Core.getInstance()
                .blackListedSpamIPs
                .add(currentConnection.getInetAddress().getHostAddress());
            new Timer()
                .schedule(
                    new TimerTask() {
                      @Override
                      public void run() {
                        Core.getInstance()
                            .blackListedSpamIPs
                            .remove(currentConnection.getInetAddress().getHostAddress());
                        System.out.println(
                            "["
                                + clientName
                                + "/"
                                + currentConnection.getInetAddress().getHostAddress()
                                + "] Removed from blacklist.");
                      }
                    },
                    Core.getInstance().MESSAGE_SPAM_BLACKLIST_BAN_MS);
            stop();
            return;
          }
        }
        lastMessage = System.currentTimeMillis();
        if (inputLine.startsWith("register:")) {
          String name = inputLine.split(":")[1].split("#")[0];
          voicePort = Integer.parseInt(inputLine.split("#")[1]);
          Core.getInstance().registeredPlayerSockets.add(name);
          Core.getInstance().unregisteredPlayerSockets.remove(name);
          clientName = name;
          System.out.println(
              "["
                  + clientName
                  + "/"
                  + currentConnection.getInetAddress().getHostAddress()
                  + "] Registered at pool "
                  + Core.getInstance().threadPools.indexOf(threadPoolExecutor)
                  + " with "
                  + threadPoolExecutor.getActiveCount()
                  + " active threads. With voice port "
                  + voicePort);
          Core.getInstance().clientThreads.add(this);
          continue;
        }

        if (Core.getInstance().registeredPlayerSockets.contains(clientName)) {
          if (inputLine.equalsIgnoreCase("disconnect")) {
            System.out.println(
                "["
                    + clientName
                    + "/"
                    + currentConnection.getInetAddress().getHostAddress()
                    + "] Disconnected");
            stop();
          } else if (inputLine.startsWith("mute") && inputLine.contains(" ")) {
            mutedPlayers.add(inputLine.replace("mute ", ""));
            System.out.println(
                "["
                    + clientName
                    + "/"
                    + currentConnection.getInetAddress().getHostAddress()
                    + "] Muted "
                    + inputLine.replace("mute ", ""));
          } else if (inputLine.startsWith("unmute") && inputLine.contains(" ")) {
            mutedPlayers.remove(inputLine.replace("unmute ", ""));
            System.out.println(
                "["
                    + clientName
                    + "/"
                    + currentConnection.getInetAddress().getHostAddress()
                    + "] Unmuted "
                    + inputLine.replace("unmute ", ""));
          } else {
            try {
              ArrayList<String> nearPlayers = new ArrayList<>();
              Gson gson = new Gson();
              nearPlayers = gson.fromJson(inputLine, nearPlayers.getClass());

              this.nearPlayers.clear();
              this.nearPlayers.addAll(nearPlayers);
            } catch (Exception e) {
              e.printStackTrace();
              if (Core.getInstance().KILL_SOCKET_IF_INVALID_MESSAGE) {
                stop();
              }
            }
          }
        }
      }
    } catch (Exception e) {
      stop();
    }
  }

  public void sendBytesToVoiceClient(byte[] bytes, int length) {
    try {
      localSequenceNumber++;
      VoicePacket rtp_packet =
          new VoicePacket(11, Math.toIntExact(localSequenceNumber), 0, bytes, length);
      int packet_length = rtp_packet.getlength();
      byte[] packet_bits = new byte[packet_length];
      rtp_packet.getpacket(packet_bits);
      DatagramPacket packet =
          new DatagramPacket(
              packet_bits, packet_length, currentConnection.getInetAddress(), voicePort);
      voiceServer.send(packet);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void stop() {
    if (!isRunning) return;
    try {
      isRunning = false;
      if (clientName != null && !clientName.isEmpty()) {
        Core.getInstance().unregisteredPlayerSockets.add(clientName);
        Core.getInstance().registeredPlayerSockets.remove(clientName);
      }
      in.close();
      out.close();
      currentConnection.close();
      System.gc();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
